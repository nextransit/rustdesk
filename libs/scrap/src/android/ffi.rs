use jni::objects::JByteBuffer;
use jni::objects::JString;
use jni::objects::JValue;
use jni::sys::{jboolean, jint, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use jni::{
    objects::{GlobalRef, JClass, JObject},
    strings::JNIString,
    JavaVM,
};

#[cfg(target_os = "android")]
use std::ffi::CString;

#[cfg(target_os = "android")]
extern "C" {
    fn __android_log_print(prio: i32, tag: *const i8, fmt: *const i8, ...) -> i32;
}

const ANDROID_LOG_INFO: i32 = 4;

#[cfg(target_os = "android")]
#[inline]
fn android_log(tag: &str, msg: &str) {
    if let (Ok(tag_c), Ok(msg_c)) = (CString::new(tag), CString::new(msg)) {
        unsafe {
            __android_log_print(
                ANDROID_LOG_INFO,
                tag_c.as_ptr() as *const i8,
                msg_c.as_ptr() as *const i8,
            );
        }
    }
}

use hbb_common::{message_proto::MultiClipboards, protobuf::Message};
use jni::errors::{Error as JniError, Result as JniResult};
use lazy_static::lazy_static;
use serde::Deserialize;
use std::ops::Not;
use std::os::raw::c_void;
use std::sync::{Mutex, RwLock};
use std::time::{Duration, Instant};

lazy_static! {
    static ref JVM: RwLock<Option<JavaVM>> = RwLock::new(None);
    static ref MAIN_SERVICE_CTX: RwLock<Option<GlobalRef>> = RwLock::new(None); // MainService -> video service / audio service / info
    static ref APPLICATION_CONTEXT: RwLock<Option<GlobalRef>> = RwLock::new(None);
    static ref VIDEO_RAW: Mutex<FrameRaw> = Mutex::new(FrameRaw::new("video", MAX_VIDEO_FRAME_TIMEOUT));
    static ref AUDIO_RAW: Mutex<FrameRaw> = Mutex::new(FrameRaw::new("audio", MAX_AUDIO_FRAME_TIMEOUT));
    static ref NDK_CONTEXT_INITED: Mutex<bool> = Default::default();
    static ref MEDIA_CODEC_INFOS: RwLock<Option<MediaCodecInfos>> = RwLock::new(None);
    static ref CLIPBOARD_MANAGER: RwLock<Option<GlobalRef>> = RwLock::new(None);
    static ref CLIPBOARDS_HOST: Mutex<Option<MultiClipboards>> = Mutex::new(None);
    static ref CLIPBOARDS_CLIENT: Mutex<Option<MultiClipboards>> = Mutex::new(None);
}

const MAX_VIDEO_FRAME_TIMEOUT: Duration = Duration::from_millis(100);
const MAX_AUDIO_FRAME_TIMEOUT: Duration = Duration::from_millis(1000);
const RAW_FRAME_STATS_LOG_INTERVAL: Duration = Duration::from_secs(5);
const FORCE_DUPLICATE_VIDEO_FRAME_INTERVAL: Duration = Duration::from_millis(66);

struct FrameRaw {
    name: &'static str,
    data: Vec<u8>,
    last_update: Instant,
    timeout: Duration,
    enable: bool,
    update_count: u64,
    take_count: u64,
    duplicate_skip_count: u64,
    duplicate_force_count: u64,
    timeout_count: u64,
    disabled_drop_count: u64,
    empty_take_count: u64,
    last_stats_log: Instant,
    last_forced_duplicate: Instant,
}

impl FrameRaw {
    fn new(name: &'static str, timeout: Duration) -> Self {
        let now = Instant::now();
        FrameRaw {
            name,
            data: Vec::new(),
            last_update: now,
            timeout,
            enable: false,
            update_count: 0,
            take_count: 0,
            duplicate_skip_count: 0,
            duplicate_force_count: 0,
            timeout_count: 0,
            disabled_drop_count: 0,
            empty_take_count: 0,
            last_stats_log: now,
            last_forced_duplicate: now,
        }
    }

    fn set_enable(&mut self, value: bool) {
        let now = Instant::now();
        self.enable = value;
        self.data.clear();
        self.last_update = now;
        self.update_count = 0;
        self.take_count = 0;
        self.duplicate_skip_count = 0;
        self.duplicate_force_count = 0;
        self.timeout_count = 0;
        self.disabled_drop_count = 0;
        self.empty_take_count = 0;
        self.last_stats_log = now;
        self.last_forced_duplicate = now;
        log::info!("MDM-RawFrameEnable name={} enabled={}", self.name, value);
    }

    fn update(&mut self, data: *mut u8, len: usize) {
        let now = Instant::now();
        if self.enable.not() {
            self.disabled_drop_count += 1;
            self.maybe_log_stats(now);
            return;
        }
        if data.is_null() || len == 0 {
            return;
        }
        self.data.resize(len, 0);
        unsafe {
            std::ptr::copy_nonoverlapping(data as *const u8, self.data.as_mut_ptr(), len);
        }
        self.last_update = now;
        self.update_count += 1;
        self.maybe_log_stats(now);
    }

    // take inner data as slice
    // release when success
    fn take<'a>(&mut self, dst: &mut Vec<u8>, last: &mut Vec<u8>) -> Option<()> {
        let now = Instant::now();
        if self.enable.not() {
            return None;
        }
        if self.data.is_empty() {
            self.empty_take_count += 1;
            self.maybe_log_stats(now);
            return None;
        }
        if self.name != "video" && self.last_update.elapsed() > self.timeout {
            self.timeout_count += 1;
            log::trace!("Failed to take {} raw,timeout!", self.name);
            self.release();
            self.maybe_log_stats(now);
            return None;
        }
        let duplicate = last.len() == self.data.len() && last.as_slice() == self.data.as_slice();
        if duplicate {
            self.duplicate_skip_count += 1;
            let force_duplicate = self.name == "video"
                && self.last_forced_duplicate.elapsed() >= FORCE_DUPLICATE_VIDEO_FRAME_INTERVAL;
            if !force_duplicate {
                self.release();
                self.maybe_log_stats(now);
                return None;
            }
            self.duplicate_force_count += 1;
            self.last_forced_duplicate = now;
        } else {
            last.resize(self.data.len(), 0);
            last.copy_from_slice(&self.data);
            self.last_forced_duplicate = now;
        }
        dst.resize(self.data.len(), 0);
        dst.copy_from_slice(&self.data);
        self.take_count += 1;
        self.release();
        self.maybe_log_stats(now);
        Some(())
    }

    fn release(&mut self) {
        if self.name != "video" {
            self.data.clear();
        }
    }

    fn maybe_log_stats(&mut self, now: Instant) {
        if now.duration_since(self.last_stats_log) < RAW_FRAME_STATS_LOG_INTERVAL {
            return;
        }
        self.last_stats_log = now;
        log::info!(
            "MDM-RawFrameStats name={} enabled={} updates={} takes={} duplicate_skips={} duplicate_forces={} timeouts={} disabled_drops={} empty_takes={} buffered_len={} last_update_age_ms={}",
            self.name,
            self.enable,
            self.update_count,
            self.take_count,
            self.duplicate_skip_count,
            self.duplicate_force_count,
            self.timeout_count,
            self.disabled_drop_count,
            self.empty_take_count,
            self.data.len(),
            self.last_update.elapsed().as_millis(),
        );
    }
}

pub fn get_video_raw<'a>(dst: &mut Vec<u8>, last: &mut Vec<u8>) -> Option<()> {
    VIDEO_RAW.lock().ok()?.take(dst, last)
}

pub fn audio_raw_is_enabled() -> bool {
    AUDIO_RAW.lock().map(|r| r.enable).unwrap_or(false)
}
pub fn get_audio_raw<'a>(dst: &mut Vec<u8>, last: &mut Vec<u8>) -> Option<()> {
    AUDIO_RAW.lock().ok()?.take(dst, last)
}

pub fn get_clipboards(client: bool) -> Option<MultiClipboards> {
    if client {
        CLIPBOARDS_CLIENT.lock().ok()?.take()
    } else {
        CLIPBOARDS_HOST.lock().ok()?.take()
    }
}

#[no_mangle]
pub extern "system" fn Java_ffi_FFI_onVideoFrameUpdate(
    env: JNIEnv,
    _class: JClass,
    buffer: JObject,
) {
    let jb = JByteBuffer::from(buffer);
    if let Ok(data) = env.get_direct_buffer_address(&jb) {
        if let Ok(len) = env.get_direct_buffer_capacity(&jb) {
            VIDEO_RAW.lock().unwrap().update(data, len);
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_ffi_FFI_convertYuv420ToRgba(
    env: JNIEnv,
    _class: JClass,
    y_buffer: JObject,
    u_buffer: JObject,
    v_buffer: JObject,
    output_buffer: JObject,
    width: jint,
    height: jint,
    y_position: jint,
    u_position: jint,
    v_position: jint,
    y_row_stride: jint,
    u_row_stride: jint,
    v_row_stride: jint,
    u_pixel_stride: jint,
    v_pixel_stride: jint,
) -> jboolean {
    if width <= 0
        || height <= 0
        || y_position < 0
        || u_position < 0
        || v_position < 0
        || y_row_stride <= 0
        || u_row_stride <= 0
        || v_row_stride <= 0
        || u_pixel_stride <= 0
        || v_pixel_stride <= 0
    {
        return JNI_FALSE;
    }

    let y_byte_buffer = JByteBuffer::from(y_buffer);
    let u_byte_buffer = JByteBuffer::from(u_buffer);
    let v_byte_buffer = JByteBuffer::from(v_buffer);
    let output_byte_buffer = JByteBuffer::from(output_buffer);
    let Ok(y_data) = env.get_direct_buffer_address(&y_byte_buffer) else {
        return JNI_FALSE;
    };
    let Ok(u_data) = env.get_direct_buffer_address(&u_byte_buffer) else {
        return JNI_FALSE;
    };
    let Ok(v_data) = env.get_direct_buffer_address(&v_byte_buffer) else {
        return JNI_FALSE;
    };
    let Ok(output_data) = env.get_direct_buffer_address(&output_byte_buffer) else {
        return JNI_FALSE;
    };
    let Ok(y_capacity) = env.get_direct_buffer_capacity(&y_byte_buffer) else {
        return JNI_FALSE;
    };
    let Ok(u_capacity) = env.get_direct_buffer_capacity(&u_byte_buffer) else {
        return JNI_FALSE;
    };
    let Ok(v_capacity) = env.get_direct_buffer_capacity(&v_byte_buffer) else {
        return JNI_FALSE;
    };
    let Ok(output_capacity) = env.get_direct_buffer_capacity(&output_byte_buffer) else {
        return JNI_FALSE;
    };

    let width_usize = width as usize;
    let height_usize = height as usize;
    let chroma_width = (width_usize + 1) / 2;
    let chroma_height = (height_usize + 1) / 2;
    let y_required = y_position as usize
        + (height_usize - 1) * y_row_stride as usize
        + width_usize;
    let u_required = u_position as usize
        + (chroma_height - 1) * u_row_stride as usize
        + (chroma_width - 1) * u_pixel_stride as usize
        + 1;
    let v_required = v_position as usize
        + (chroma_height - 1) * v_row_stride as usize
        + (chroma_width - 1) * v_pixel_stride as usize
        + 1;
    let output_len = width_usize * height_usize * 4;
    if y_capacity < y_required
        || u_capacity < u_required
        || v_capacity < v_required
        || output_capacity < output_len
    {
        return JNI_FALSE;
    }

    let mut temporary_u = Vec::new();
    let mut temporary_v = Vec::new();
    let (u_plane, v_plane, output_u_stride, output_v_stride) =
        if u_pixel_stride == 1 && v_pixel_stride == 1 {
            (
                unsafe { u_data.add(u_position as usize) as *const u8 },
                unsafe { v_data.add(v_position as usize) as *const u8 },
                u_row_stride,
                v_row_stride,
            )
        } else {
            temporary_u.resize(chroma_width * chroma_height, 128);
            temporary_v.resize(chroma_width * chroma_height, 128);
            for row in 0..chroma_height {
                for column in 0..chroma_width {
                    let target = row * chroma_width + column;
                    let u_source = u_position as usize
                        + row * u_row_stride as usize
                        + column * u_pixel_stride as usize;
                    let v_source = v_position as usize
                        + row * v_row_stride as usize
                        + column * v_pixel_stride as usize;
                    temporary_u[target] = unsafe { *u_data.add(u_source) };
                    temporary_v[target] = unsafe { *v_data.add(v_source) };
                }
            }
            (
                temporary_u.as_ptr(),
                temporary_v.as_ptr(),
                chroma_width as jint,
                chroma_width as jint,
            )
        };

    let result = unsafe {
        crate::I420ToABGR(
            y_data.add(y_position as usize) as *const u8,
            y_row_stride,
            u_plane,
            output_u_stride,
            v_plane,
            output_v_stride,
            output_data,
            width * 4,
            width,
            height,
        )
    };
    if result == 0 {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[no_mangle]
pub extern "system" fn Java_ffi_FFI_onAudioFrameUpdate(
    env: JNIEnv,
    _class: JClass,
    buffer: JObject,
) {
    static mut FIRST: i32 = 0;
    let jb = JByteBuffer::from(buffer);
    if let Ok(data) = env.get_direct_buffer_address(&jb) {
        if let Ok(len) = env.get_direct_buffer_capacity(&jb) {
            AUDIO_RAW.lock().unwrap().update(data, len);
            unsafe {
                if FIRST < 3 {
                    FIRST += 1;
                    android_log("rustdesk_native", &format!("Java_ffi_FFI_onAudioFrameUpdate first len={}", len));
                }
            }
        } else {
            android_log("rustdesk_native", "onAudioFrameUpdate get_direct_buffer_capacity failed");
        }
    } else {
        android_log("rustdesk_native", "onAudioFrameUpdate get_direct_buffer_address failed");
    }
}

#[no_mangle]
pub extern "system" fn Java_ffi_FFI_onClipboardUpdate(
    env: JNIEnv,
    _class: JClass,
    buffer: JByteBuffer,
) {
    if let Ok(data) = env.get_direct_buffer_address(&buffer) {
        if let Ok(len) = env.get_direct_buffer_capacity(&buffer) {
            let data = unsafe { std::slice::from_raw_parts(data, len) };
            if let Ok(clips) = MultiClipboards::parse_from_bytes(&data[1..]) {
                let is_client = data[0] == 1;
                if is_client {
                    *CLIPBOARDS_CLIENT.lock().unwrap() = Some(clips);
                } else {
                    *CLIPBOARDS_HOST.lock().unwrap() = Some(clips);
                }
            }
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_ffi_FFI_setFrameRawEnable(
    env: JNIEnv,
    _class: JClass,
    name: JString,
    value: jboolean,
) {
    android_log("rustdesk_native", "Java_ffi_FFI_setFrameRawEnable entered");
    let mut env = env;
    if let Ok(name) = env.get_string(&name) {
        let name: String = name.into();
        let value = value.eq(&1);
        android_log("rustdesk_native", &format!("Java_ffi_FFI_setFrameRawEnable name={} value={}", name, value));
        if name.eq("video") {
            VIDEO_RAW.lock().unwrap().set_enable(value);
        } else if name.eq("audio") {
            AUDIO_RAW.lock().unwrap().set_enable(value);
        }
    } else {
        android_log("rustdesk_native", "Java_ffi_FFI_setFrameRawEnable get_string failed");
    };
}

#[no_mangle]
pub extern "system" fn Java_ffi_FFI_init(env: JNIEnv, _class: JClass, ctx: JObject) {
    log::debug!("MainService init from java");
    if let Ok(jvm) = env.get_java_vm() {
        let java_vm = jvm.get_java_vm_pointer() as *mut c_void;
        let mut jvm_lock = JVM.write().unwrap();
        if jvm_lock.is_none() {
            *jvm_lock = Some(jvm);
        }
        drop(jvm_lock);
        if let Ok(context) = env.new_global_ref(ctx) {
            let context_jobject = context.as_obj().as_raw() as *mut c_void;
            *MAIN_SERVICE_CTX.write().unwrap() = Some(context);
            init_ndk_context(java_vm, context_jobject);
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_ffi_FFI_setClipboardManager(
    env: JNIEnv,
    _class: JClass,
    clipboard_manager: JObject,
) {
    log::debug!("ClipboardManager init from java");
    if let Ok(jvm) = env.get_java_vm() {
        let java_vm = jvm.get_java_vm_pointer() as *mut c_void;
        let mut jvm_lock = JVM.write().unwrap();
        if jvm_lock.is_none() {
            *jvm_lock = Some(jvm);
        }
        drop(jvm_lock);
        if let Ok(manager) = env.new_global_ref(clipboard_manager) {
            *CLIPBOARD_MANAGER.write().unwrap() = Some(manager);
        }
    }
}

#[derive(Debug, Deserialize, Clone)]
pub struct MediaCodecInfo {
    pub name: String,
    pub is_encoder: bool,
    #[serde(default)]
    pub hw: Option<bool>, // api 29+
    pub mime_type: String,
    pub surface: bool,
    pub nv12: bool,
    #[serde(default)]
    pub low_latency: Option<bool>, // api 30+, decoder
    pub min_bitrate: u32,
    pub max_bitrate: u32,
    pub min_width: usize,
    pub max_width: usize,
    pub min_height: usize,
    pub max_height: usize,
}

#[derive(Debug, Deserialize, Clone)]
pub struct MediaCodecInfos {
    pub version: usize,
    pub w: usize, // aligned
    pub h: usize, // aligned
    pub codecs: Vec<MediaCodecInfo>,
}

#[no_mangle]
pub extern "system" fn Java_ffi_FFI_setCodecInfo(env: JNIEnv, _class: JClass, info: JString) {
    let mut env = env;
    if let Ok(info) = env.get_string(&info) {
        let info: String = info.into();
        if let Ok(infos) = serde_json::from_str::<MediaCodecInfos>(&info) {
            *MEDIA_CODEC_INFOS.write().unwrap() = Some(infos);
        }
    }
}

pub fn get_codec_info() -> Option<MediaCodecInfos> {
    MEDIA_CODEC_INFOS.read().unwrap().as_ref().cloned()
}

pub fn clear_codec_info() {
    *MEDIA_CODEC_INFOS.write().unwrap() = None;
}

// another way to fix "reference table overflow" error caused by new_string and call_main_service_pointer_input frequently calld
// is below, but here I change kind from string to int for performance
/*
        env.with_local_frame(10, || {
            let kind = env.new_string(kind)?;
            env.call_method(
                ctx,
                "rustPointerInput",
                "(Ljava/lang/String;III)V",
                &[
                    JValue::Object(&JObject::from(kind)),
                    JValue::Int(mask),
                    JValue::Int(x),
                    JValue::Int(y),
                ],
            )?;
            Ok(JObject::null())
        })?;
*/
pub fn call_main_service_pointer_input(kind: &str, mask: i32, x: i32, y: i32) -> JniResult<()> {
    if let (Some(jvm), Some(ctx)) = (
        JVM.read().unwrap().as_ref(),
        MAIN_SERVICE_CTX.read().unwrap().as_ref(),
    ) {
        let mut env = jvm.attach_current_thread_as_daemon()?;
        let kind = if kind == "touch" { 0 } else { 1 };
        env.call_method(
            ctx,
            "rustPointerInput",
            "(IIII)V",
            &[
                JValue::Int(kind),
                JValue::Int(mask),
                JValue::Int(x),
                JValue::Int(y),
            ],
        )?;
        return Ok(());
    } else {
        return Err(JniError::ThrowFailed(-1));
    }
}

pub fn call_main_service_key_event(data: &[u8]) -> JniResult<()> {
    if let (Some(jvm), Some(ctx)) = (
        JVM.read().unwrap().as_ref(),
        MAIN_SERVICE_CTX.read().unwrap().as_ref(),
    ) {
        let mut env = jvm.attach_current_thread_as_daemon()?;
        let data = env.byte_array_from_slice(data)?;

        env.call_method(
            ctx,
            "rustKeyEventInput",
            "([B)V",
            &[JValue::Object(&JObject::from(data))],
        )?;
        return Ok(());
    } else {
        return Err(JniError::ThrowFailed(-1));
    }
}

fn _call_clipboard_manager<S, T>(name: S, sig: T, args: &[JValue]) -> JniResult<()>
where
    S: Into<JNIString>,
    T: Into<JNIString> + AsRef<str>,
{
    if let (Some(jvm), Some(cm)) = (
        JVM.read().unwrap().as_ref(),
        CLIPBOARD_MANAGER.read().unwrap().as_ref(),
    ) {
        let mut env = jvm.attach_current_thread()?;
        env.call_method(cm, name, sig, args)?;
        return Ok(());
    } else {
        return Err(JniError::ThrowFailed(-1));
    }
}

pub fn call_clipboard_manager_update_clipboard(data: &[u8]) -> JniResult<()> {
    if let (Some(jvm), Some(cm)) = (
        JVM.read().unwrap().as_ref(),
        CLIPBOARD_MANAGER.read().unwrap().as_ref(),
    ) {
        let mut env = jvm.attach_current_thread()?;
        let data = env.byte_array_from_slice(data)?;

        env.call_method(
            cm,
            "rustUpdateClipboard",
            "([B)V",
            &[JValue::Object(&JObject::from(data))],
        )?;
        return Ok(());
    } else {
        return Err(JniError::ThrowFailed(-1));
    }
}

pub fn call_clipboard_manager_enable_client_clipboard(enable: bool) -> JniResult<()> {
    _call_clipboard_manager(
        "rustEnableClientClipboard",
        "(Z)V",
        &[JValue::Bool(jboolean::from(enable))],
    )
}

pub fn call_main_service_get_by_name(name: &str) -> JniResult<String> {
    if let (Some(jvm), Some(ctx)) = (
        JVM.read().unwrap().as_ref(),
        MAIN_SERVICE_CTX.read().unwrap().as_ref(),
    ) {
        let mut env = jvm.attach_current_thread_as_daemon()?;
        let res = env.with_local_frame(10, |env| -> JniResult<String> {
            let name = env.new_string(name)?;
            let res = env
                .call_method(
                    ctx,
                    "rustGetByName",
                    "(Ljava/lang/String;)Ljava/lang/String;",
                    &[JValue::Object(&JObject::from(name))],
                )?
                .l()?;
            let res = JString::from(res);
            let res = env.get_string(&res)?;
            let res = res.to_string_lossy().to_string();
            Ok(res)
        })?;
        Ok(res)
    } else {
        return Err(JniError::ThrowFailed(-1));
    }
}

pub fn call_main_service_set_by_name(
    name: &str,
    arg1: Option<&str>,
    arg2: Option<&str>,
) -> JniResult<()> {
    if let (Some(jvm), Some(ctx)) = (
        JVM.read().unwrap().as_ref(),
        MAIN_SERVICE_CTX.read().unwrap().as_ref(),
    ) {
        let mut env = jvm.attach_current_thread_as_daemon()?;
        env.with_local_frame(10, |env| -> JniResult<()> {
            let name = env.new_string(name)?;
            let arg1 = env.new_string(arg1.unwrap_or(""))?;
            let arg2 = env.new_string(arg2.unwrap_or(""))?;

            env.call_method(
                ctx,
                "rustSetByName",
                "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V",
                &[
                    JValue::Object(&JObject::from(name)),
                    JValue::Object(&JObject::from(arg1)),
                    JValue::Object(&JObject::from(arg2)),
                ],
            )?;
            Ok(())
        })?;
        return Ok(());
    } else {
        return Err(JniError::ThrowFailed(-1));
    }
}

// Difference between MainService, MainActivity, JNI_OnLoad:
//  jvm is the same, ctx is differen and ctx of JNI_OnLoad is null.
//  cpal: all three works
//  Service(GetByName, ...): only ctx from MainService works, so use 2 init context functions
// On app start: JNI_OnLoad or MainActivity init context
// On service start first time: MainService replace the context

fn init_ndk_context(java_vm: *mut c_void, context_jobject: *mut c_void) {
    let mut lock = NDK_CONTEXT_INITED.lock().unwrap();
    if *lock {
        unsafe {
            ndk_context::release_android_context();
        }
        *lock = false;
    }
    unsafe {
        ndk_context::initialize_android_context(java_vm, context_jobject);
        #[cfg(feature = "hwcodec")]
        hwcodec::android::ffmpeg_set_java_vm(java_vm);
    }
    *lock = true;
}

fn try_init_rustls_platform_verifier(env: &mut JNIEnv, context_jobject: *mut c_void) {
    use hbb_common::config::ANDROID_RUSTLS_PLATFORM_VERIFIER_INITIALIZED as INITIALIZED;
    use std::sync::atomic::Ordering;
    let initialized = INITIALIZED.load(Ordering::Relaxed);
    if !initialized {
        let ctx_for_rustls = unsafe { JObject::from_raw(context_jobject as jni::sys::jobject) };
        if let Err(e) =
            hbb_common::rustls_platform_verifier::android::init_hosted(env, ctx_for_rustls)
        {
            log::error!("Failed to initialize rustls-platform-verifier: {:?}", e);
        } else {
            INITIALIZED.store(true, Ordering::Relaxed);
            log::info!("rustls-platform-verifier initialized successfully");
        }
    }
}

// https://cjycode.com/flutter_rust_bridge/guides/how-to/ndk-init
#[no_mangle]
pub extern "C" fn JNI_OnLoad(vm: jni::JavaVM, res: *mut std::os::raw::c_void) -> jni::sys::jint {
    if let Ok(env) = vm.get_env() {
        let vm = vm.get_java_vm_pointer() as *mut std::os::raw::c_void;
        init_ndk_context(vm, res);
    }
    jni::JNIVersion::V6.into()
}

#[no_mangle]
pub extern "system" fn Java_ffi_FFI_onAppStart(mut env: JNIEnv, _class: JClass, ctx: JObject) {
    if ctx.is_null() {
        log::error!("application context is null");
        return;
    }
    if APPLICATION_CONTEXT.read().unwrap().is_some() {
        log::info!("application context already initialized");
        return;
    }
    if let Ok(jvm) = env.get_java_vm() {
        if let Ok(context) = env.new_global_ref(ctx) {
            let java_vm = jvm.get_java_vm_pointer() as *mut c_void;
            let context_jobject = context.as_obj().as_raw() as *mut c_void;
            *APPLICATION_CONTEXT.write().unwrap() = Some(context);
            try_init_rustls_platform_verifier(&mut env, context_jobject);
        }
    }
}
