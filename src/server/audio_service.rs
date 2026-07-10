// both soundio and cpal use wasapi on windows and coreaudio on mac, they do not support loopback.
// libpulseaudio support loopback because pulseaudio is a standalone audio service with some
// configuration, but need to install the library and start the service on OS, not a good choice.
// windows: https://docs.microsoft.com/en-us/windows/win32/coreaudio/loopback-recording
// mac: https://github.com/mattingalls/Soundflower
// https://docs.microsoft.com/en-us/windows/win32/api/audioclient/nn-audioclient-iaudioclient
// https://github.com/ExistentialAudio/BlackHole

// if pactl not work, please run
// sudo apt-get --purge --reinstall install pulseaudio
// https://askubuntu.com/questions/403416/how-to-listen-live-sounds-from-input-from-external-sound-card
// https://wiki.debian.org/audio-loopback
// https://github.com/krruzic/pulsectl

use super::*;

#[cfg(target_os = "android")]
use std::ffi::CString;
#[cfg(target_os = "android")]
extern "C" {
    fn __android_log_print(prio: i32, tag: *const i8, fmt: *const i8, ...) -> i32;
}
#[cfg(target_os = "android")]
#[inline]
fn android_log(tag: &str, msg: &str) {
    if let (Ok(tag_c), Ok(msg_c)) = (CString::new(tag), CString::new(msg)) {
        unsafe {
            __android_log_print(
                4,
                tag_c.as_ptr() as *const i8,
                msg_c.as_ptr() as *const i8,
            );
        }
    }
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
use hbb_common::anyhow::anyhow;
use magnum_opus::{Application::*, Channels::*, Encoder};
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant};

pub const NAME: &'static str = "audio";
pub const AUDIO_DATA_SIZE_U8: usize = 960 * 4; // 10ms in 48000 stereo
static RESTARTING: AtomicBool = AtomicBool::new(false);

lazy_static::lazy_static! {
    static ref VOICE_CALL_INPUT_DEVICE: Arc::<Mutex::<Option<String>>> = Default::default();
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn new() -> GenericService {
    let svc = EmptyExtraFieldService::new(NAME.to_owned(), true);
    GenericService::repeat::<cpal_impl::State, _, _>(&svc.clone(), 33, cpal_impl::run);
    svc.sp
}

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn new() -> GenericService {
    let svc = EmptyExtraFieldService::new(NAME.to_owned(), true);
    GenericService::run(&svc.clone(), pa_impl::run);
    svc.sp
}

#[inline]
pub fn get_voice_call_input_device() -> Option<String> {
    VOICE_CALL_INPUT_DEVICE.lock().unwrap().clone()
}

#[inline]
pub fn set_voice_call_input_device(device: Option<String>, set_if_present: bool) {
    if !set_if_present && VOICE_CALL_INPUT_DEVICE.lock().unwrap().is_some() {
        return;
    }

    if *VOICE_CALL_INPUT_DEVICE.lock().unwrap() == device {
        return;
    }
    *VOICE_CALL_INPUT_DEVICE.lock().unwrap() = device;
    restart();
}

#[inline]
fn get_audio_input() -> String {
    VOICE_CALL_INPUT_DEVICE
        .lock()
        .unwrap()
        .clone()
        .unwrap_or(Config::get_option("audio-input"))
}

pub fn restart() {
    log::info!("restart the audio service, freezing now...");
    if RESTARTING.load(Ordering::SeqCst) {
        return;
    }
    RESTARTING.store(true, Ordering::SeqCst);
}

#[cfg(any(target_os = "linux", target_os = "android"))]
mod pa_impl {
    use super::*;


    // SAFETY: constrains of hbb_common::mem::aligned_u8_vec must be held
    unsafe fn align_to_32(data: Vec<u8>) -> Vec<u8> {
        if (data.as_ptr() as usize & 3) == 0 {
            return data;
        }

        let mut buf = vec![];
        buf = unsafe { hbb_common::mem::aligned_u8_vec(data.len(), 4) };
        buf.extend_from_slice(data.as_ref());
        buf
    }

    #[tokio::main(flavor = "current_thread")]
    pub async fn run(sp: EmptyExtraFieldService) -> ResultType<()> {
        hbb_common::sleep(0.1).await; // one moment to wait for _pa ipc
        RESTARTING.store(false, Ordering::SeqCst);
        #[cfg(target_os = "linux")]
        let mut stream = crate::ipc::connect(1000, "_pa").await?;
        unsafe {
            AUDIO_ZERO_COUNT = 0;
        }
        let mut encoder = Encoder::new(crate::platform::PA_SAMPLE_RATE, Stereo, LowDelay)?;
        #[cfg(target_os = "linux")]
        allow_err!(
            stream
                .send(&crate::ipc::Data::Config((
                    "audio-input".to_owned(),
                    Some(super::get_audio_input())
                )))
                .await
        );
        #[cfg(target_os = "linux")]
        let zero_audio_frame: Vec<f32> = vec![0.; AUDIO_DATA_SIZE_U8 / 4];
        #[cfg(target_os = "android")]
        let mut android_data = vec![];
        #[cfg(target_os = "android")]
        let mut stats = AndroidAudioServiceStats::new();
        while sp.ok() && !RESTARTING.load(Ordering::SeqCst) {
            sp.snapshot(|sps| {
                sps.send(create_format_msg(crate::platform::PA_SAMPLE_RATE, 2));
                #[cfg(target_os = "android")]
                log::info!(
                    "MDM-AudioServiceFormat sample_rate={} channels=2",
                    crate::platform::PA_SAMPLE_RATE
                );
                #[cfg(target_os = "android")]
                android_log(
                    "rustdesk_audio",
                    &format!(
                        "MDM-AudioServiceFormat sample_rate={} channels=2",
                        crate::platform::PA_SAMPLE_RATE
                    ),
                );
                Ok(())
            })?;

            #[cfg(target_os = "linux")]
            if let Ok(data) = stream.next_raw().await {
                if data.len() == 0 {
                    send_f32(&zero_audio_frame, &mut encoder, &sp);
                    continue;
                }

                if data.len() != AUDIO_DATA_SIZE_U8 {
                    continue;
                }

                let data = unsafe { align_to_32(data.into()) };
                let data = unsafe {
                    std::slice::from_raw_parts::<f32>(data.as_ptr() as _, data.len() / 4)
                };
                send_f32(data, &mut encoder, &sp);
            }

            #[cfg(target_os = "android")]
            {
                // MDM v6: pace the audio run loop at 50 pkt/s only while the
                // AudioRecord is actually producing frames OR during the
                // initial 1s filler after a stall. When the recorder is
                // stopped (FFI enable=false), drop into a 100ms idle sleep so
                // the MediaProjection video encoder is not starved by us
                // busy-looping silence-pads.
                let target_frame_floats = crate::platform::PA_SAMPLE_RATE as usize
                    * 2 /*channels*/
                    * 20 /*ms*/
                    / 1000;
                if !scrap::android::ffi::audio_raw_is_enabled() {
                    if stats.idle_logged.elapsed() >= Duration::from_secs(5) {
                        log::info!(
                            "MDM-AudioServiceIdle empty_takes={} raw_takes={} opus_packets={} silence_pads={}",
                            stats.empty_takes,
                            stats.raw_takes,
                            stats.opus_packets,
                            stats.silence_pads,
                        );
                        android_log(
                            "rustdesk_audio",
                            &format!(
                                "MDM-AudioServiceIdle empty_takes={} raw_takes={} opus_packets={} silence_pads={}",
                                stats.empty_takes,
                                stats.raw_takes,
                                stats.opus_packets,
                                stats.silence_pads,
                            ),
                        );
                        stats.idle_logged = Instant::now();
                    }
                    hbb_common::sleep(0.1).await;
                    continue;
                }
                let now = Instant::now();
                let next_due = stats.next_frame_due;
                if now < next_due {
                    let to_sleep = (next_due - now).as_millis().min(20) as u64;
                    if to_sleep > 0 {
                        hbb_common::sleep(to_sleep as f32 / 1000.0).await;
                    }
                    continue;
                }
                let _ = scrap::android::ffi::get_audio_raw(&mut android_data, &mut vec![]);
                if !android_data.is_empty() {
                    stats.raw_take(android_data.len());
                    let data = unsafe {
                        android_data = align_to_32(android_data);
                        std::slice::from_raw_parts::<f32>(
                            android_data.as_ptr() as _,
                            android_data.len() / 4,
                        )
                    };
                    let sent = send_f32(data, &mut encoder, &sp);
                    stats.opus_sent(sent);
                    stats.missed_frames = 0;
                } else {
                    stats.empty_take();
                    stats.missed_frames += 1;
                    // Cap silence pad at 50 frames (1s) after the last raw
                    // frame; afterwards sleep 100ms so we do not starve the
                    // video encoder on devices that never deliver raw PCM.
                    // P1(mdm-noise-floor): use true 0.0 instead of 1.0e-7.
                    // Opus would otherwise emit a non-DTX packet; the client
                    // decoder turns that into persistent hiss whenever the
                    // audio is "enabled" but no real PCM is arriving.
                    if stats.missed_frames <= 50 {
                        let filler: Vec<f32> = vec![0.0; target_frame_floats];
                        let sent = send_f32(&filler, &mut encoder, &sp);
                        if sent > 0 {
                            stats.opus_sent(sent);
                            stats.silence_pads += 1;
                        }
                    } else if stats.cooldown_logged.elapsed() >= Duration::from_secs(5) {
                        log::warn!(
                            "MDM-AudioServiceCooldown empty_takes={} raw_takes={} missed={} silence_pads={}",
                            stats.empty_takes,
                            stats.raw_takes,
                            stats.missed_frames,
                            stats.silence_pads,
                        );
                        android_log(
                            "rustdesk_audio",
                            &format!(
                                "MDM-AudioServiceCooldown empty_takes={} raw_takes={} missed={} silence_pads={}",
                                stats.empty_takes,
                                stats.raw_takes,
                                stats.missed_frames,
                                stats.silence_pads,
                            ),
                        );
                        stats.cooldown_logged = Instant::now();
                    }
                }
                stats.next_frame_due = now + Duration::from_millis(20);
                if stats.last_raw_take.elapsed() > Duration::from_millis(500)
                    && stats.empty_takes_since_raw >= 5
                {
                    log::warn!(
                        "MDM-AudioServiceStall empty_takes={} raw_takes={} since_last_raw_ms={:?} missed={} silence_pads={}",
                        stats.empty_takes,
                        stats.raw_takes,
                        stats.last_raw_take.elapsed(),
                        stats.missed_frames,
                        stats.silence_pads
                    );
                    android_log(
                        "rustdesk_audio",
                        &format!(
                            "MDM-AudioServiceStall empty_takes={} raw_takes={} since_last_raw_ms={} missed={} silence_pads={}",
                            stats.empty_takes,
                            stats.raw_takes,
                            stats.last_raw_take.elapsed().as_millis(),
                            stats.missed_frames,
                            stats.silence_pads
                        ),
                    );
                }
            }
        }
        Ok(())
    }

    #[cfg(target_os = "android")]
    struct AndroidAudioServiceStats {
        raw_takes: u64,
        raw_bytes: u64,
        empty_takes: u64,
        opus_packets: u64,
        last_log: Instant,
        last_raw_take: Instant,
        empty_takes_since_raw: u64,
        pcm_buffered_samples: usize,
        missed_frames: u64,
        silence_pads: u64,
        next_frame_due: Instant,
        idle_logged: Instant,
        cooldown_logged: Instant,
    }

    #[cfg(target_os = "android")]
    impl AndroidAudioServiceStats {
        fn new() -> Self {
            Self {
                raw_takes: 0,
                raw_bytes: 0,
                empty_takes: 0,
                opus_packets: 0,
                last_log: Instant::now(),
                last_raw_take: Instant::now(),
                empty_takes_since_raw: 0,
                pcm_buffered_samples: 0,
                missed_frames: 0,
                silence_pads: 0,
                next_frame_due: Instant::now(),
                idle_logged: Instant::now(),
                cooldown_logged: Instant::now(),
            }
        }

        fn raw_take(&mut self, bytes: usize) {
            self.raw_takes += 1;
            self.raw_bytes += bytes as u64;
            self.last_raw_take = Instant::now();
            self.empty_takes_since_raw = 0;
            self.maybe_log(bytes, "raw");
        }

        fn empty_take(&mut self) {
            self.empty_takes += 1;
            self.empty_takes_since_raw += 1;
            self.maybe_log(0, "empty");
        }

        fn opus_sent(&mut self, packets: usize) {
            self.opus_packets += packets as u64;
            self.maybe_log(0, "opus");
        }

        fn maybe_log(&mut self, last_raw_bytes: usize, reason: &str) {
            if self.raw_takes <= 1 || self.last_log.elapsed() >= Duration::from_secs(5) {
                self.last_log = Instant::now();
                log::info!(
                    "MDM-AudioServiceStats reason={} raw_takes={} raw_bytes={} empty_takes={} opus_packets={} last_raw_bytes={}",
                    reason,
                    self.raw_takes,
                    self.raw_bytes,
                    self.empty_takes,
                    self.opus_packets,
                    last_raw_bytes
                );
                #[cfg(target_os = "android")]
                android_log("rustdesk_audio", &format!(
                    "MDM-AudioServiceStats reason={} raw_takes={} raw_bytes={} empty_takes={} opus_packets={} last_raw_bytes={}",
                    reason, self.raw_takes, self.raw_bytes, self.empty_takes, self.opus_packets, last_raw_bytes
                ));
            }
        }
    }
}

#[inline]
#[cfg(feature = "screencapturekit")]
pub fn is_screen_capture_kit_available() -> bool {
    cpal::available_hosts()
        .iter()
        .any(|host| *host == cpal::HostId::ScreenCaptureKit)
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
mod cpal_impl {
    use self::service::{Reset, ServiceSwap};
    use super::*;


    use cpal::{
        traits::{DeviceTrait, HostTrait, StreamTrait},
        BufferSize, Device, Host, InputCallbackInfo, StreamConfig, SupportedStreamConfig,
    };

    lazy_static::lazy_static! {
        static ref HOST: Host = cpal::default_host();
        static ref INPUT_BUFFER: Arc<Mutex<std::collections::VecDeque<f32>>> = Default::default();
    }

    #[cfg(feature = "screencapturekit")]
    lazy_static::lazy_static! {
        static ref HOST_SCREEN_CAPTURE_KIT: Result<Host, cpal::HostUnavailable> = cpal::host_from_id(cpal::HostId::ScreenCaptureKit);
    }

    #[derive(Default)]
    pub struct State {
        stream: Option<(Box<dyn StreamTrait>, Arc<Message>)>,
    }

    impl super::service::Reset for State {
        fn reset(&mut self) {
            self.stream.take();
        }
    }

    fn run_restart(sp: EmptyExtraFieldService, state: &mut State) -> ResultType<()> {
        state.reset();
        sp.snapshot(|_sps: ServiceSwap<_>| Ok(()))?;
        match &state.stream {
            None => {
                state.stream = Some(play(&sp)?);
            }
            _ => {}
        }
        if let Some((_, format)) = &state.stream {
            sp.send_shared(format.clone());
        }
        RESTARTING.store(false, Ordering::SeqCst);
        Ok(())
    }

    fn run_serv_snapshot(sp: EmptyExtraFieldService, state: &mut State) -> ResultType<()> {
        sp.snapshot(|sps| {
            match &state.stream {
                None => {
                    state.stream = Some(play(&sp)?);
                }
                _ => {}
            }
            if let Some((_, format)) = &state.stream {
                sps.send_shared(format.clone());
            }
            Ok(())
        })?;
        Ok(())
    }

    pub fn run(sp: EmptyExtraFieldService, state: &mut State) -> ResultType<()> {
        if !RESTARTING.load(Ordering::SeqCst) {
            run_serv_snapshot(sp, state)
        } else {
            run_restart(sp, state)
        }
    }

    fn send(
        data: Vec<f32>,
        sample_rate0: u32,
        sample_rate: u32,
        device_channel: u16,
        encode_channel: u16,
        encoder: &mut Encoder,
        sp: &GenericService,
    ) {
        let mut data = data;
        if sample_rate0 != sample_rate {
            data = crate::common::audio_resample(&data, sample_rate0, sample_rate, device_channel);
        }
        if device_channel != encode_channel {
            data = crate::common::audio_rechannel(
                data,
                sample_rate,
                sample_rate,
                device_channel,
                encode_channel,
            )
        }
        send_f32(&data, encoder, sp);
    }

    #[cfg(feature = "screencapturekit")]
    fn get_device() -> ResultType<(Device, SupportedStreamConfig)> {
        let audio_input = super::get_audio_input();
        if !audio_input.is_empty() {
            return get_audio_input(&audio_input);
        }
        if !is_screen_capture_kit_available() {
            return get_audio_input("");
        }
        let device = HOST_SCREEN_CAPTURE_KIT
            .as_ref()?
            .default_input_device()
            .with_context(|| "Failed to get default input device for loopback")?;
        let format = device
            .default_input_config()
            .map_err(|e| anyhow!(e))
            .with_context(|| "Failed to get input output format")?;
        log::info!("Default input format: {:?}", format);
        Ok((device, format))
    }

    #[cfg(windows)]
    fn get_device() -> ResultType<(Device, SupportedStreamConfig)> {
        let audio_input = super::get_audio_input();
        if !audio_input.is_empty() {
            return get_audio_input(&audio_input);
        }
        let device = HOST
            .default_output_device()
            .with_context(|| "Failed to get default output device for loopback")?;
        log::info!(
            "Default output device: {}",
            device.name().unwrap_or("".to_owned())
        );
        let format = device
            .default_output_config()
            .map_err(|e| anyhow!(e))
            .with_context(|| "Failed to get default output format")?;
        log::info!("Default output format: {:?}", format);
        Ok((device, format))
    }

    #[cfg(not(any(windows, feature = "screencapturekit")))]
    fn get_device() -> ResultType<(Device, SupportedStreamConfig)> {
        let audio_input = super::get_audio_input();
        get_audio_input(&audio_input)
    }

    fn get_audio_input(audio_input: &str) -> ResultType<(Device, SupportedStreamConfig)> {
        let mut device = None;
        #[cfg(feature = "screencapturekit")]
        if !audio_input.is_empty() && is_screen_capture_kit_available() {
            for d in HOST_SCREEN_CAPTURE_KIT
                .as_ref()?
                .devices()
                .with_context(|| "Failed to get audio devices")?
            {
                if d.name().unwrap_or("".to_owned()) == audio_input {
                    device = Some(d);
                    break;
                }
            }
        }
        if device.is_none() && !audio_input.is_empty() {
            for d in HOST
                .devices()
                .with_context(|| "Failed to get audio devices")?
            {
                if d.name().unwrap_or("".to_owned()) == audio_input {
                    device = Some(d);
                    break;
                }
            }
        }
        let device = device.unwrap_or(
            HOST.default_input_device()
                .with_context(|| "Failed to get default input device for loopback")?,
        );
        log::info!("Input device: {}", device.name().unwrap_or("".to_owned()));
        let format = device
            .default_input_config()
            .map_err(|e| anyhow!(e))
            .with_context(|| "Failed to get default input format")?;
        log::info!("Default input format: {:?}", format);
        Ok((device, format))
    }

    fn play(sp: &GenericService) -> ResultType<(Box<dyn StreamTrait>, Arc<Message>)> {
        use cpal::SampleFormat::*;
        let (device, config) = get_device()?;
        let sp = sp.clone();
        // Sample rate must be one of 8000, 12000, 16000, 24000, or 48000.
        let sample_rate_0 = config.sample_rate().0;
        let sample_rate = if sample_rate_0 < 12000 {
            8000
        } else if sample_rate_0 < 16000 {
            12000
        } else if sample_rate_0 < 24000 {
            16000
        } else if sample_rate_0 < 48000 {
            24000
        } else {
            48000
        };
        let ch = if config.channels() > 1 { Stereo } else { Mono };
        let stream = match config.sample_format() {
            I8 => build_input_stream::<i8>(device, &config, sp, sample_rate, ch)?,
            I16 => build_input_stream::<i16>(device, &config, sp, sample_rate, ch)?,
            I32 => build_input_stream::<i32>(device, &config, sp, sample_rate, ch)?,
            I64 => build_input_stream::<i64>(device, &config, sp, sample_rate, ch)?,
            U8 => build_input_stream::<u8>(device, &config, sp, sample_rate, ch)?,
            U16 => build_input_stream::<u16>(device, &config, sp, sample_rate, ch)?,
            U32 => build_input_stream::<u32>(device, &config, sp, sample_rate, ch)?,
            U64 => build_input_stream::<u64>(device, &config, sp, sample_rate, ch)?,
            F32 => build_input_stream::<f32>(device, &config, sp, sample_rate, ch)?,
            F64 => build_input_stream::<f64>(device, &config, sp, sample_rate, ch)?,
            f => bail!("unsupported audio format: {:?}", f),
        };
        stream.play()?;
        Ok((
            Box::new(stream),
            Arc::new(create_format_msg(sample_rate, ch as _)),
        ))
    }

    fn build_input_stream<T>(
        device: cpal::Device,
        config: &cpal::SupportedStreamConfig,
        sp: GenericService,
        sample_rate: u32,
        encode_channel: magnum_opus::Channels,
    ) -> ResultType<cpal::Stream>
    where
        T: cpal::SizedSample + dasp::sample::ToSample<f32>,
    {
        let err_fn = move |err| {
            // too many UnknownErrno, will improve later
            log::trace!("an error occurred on stream: {}", err);
        };
        let sample_rate_0 = config.sample_rate().0;
        log::debug!("Audio sample rate : {}", sample_rate);
        unsafe {
            AUDIO_ZERO_COUNT = 0;
        }
        let device_channel = config.channels();
        let mut encoder = Encoder::new(sample_rate, encode_channel, LowDelay)?;
        // https://www.opus-codec.org/docs/html_api/group__opusencoder.html#gace941e4ef26ed844879fde342ffbe546
        // https://chromium.googlesource.com/chromium/deps/opus/+/1.1.1/include/opus.h
        // Do not set `frame_size = sample_rate as usize / 100;`
        // Because we find `sample_rate as usize / 100` will cause encoder error in `encoder.encode_vec_float()` sometimes.
        // https://github.com/xiph/opus/blob/2554a89e02c7fc30a980b4f7e635ceae1ecba5d6/src/opus_encoder.c#L725
        let frame_size = sample_rate_0 as usize / 100; // 10 ms
        let encode_len = frame_size * encode_channel as usize;
        let rechannel_len = encode_len * device_channel as usize / encode_channel as usize;
        INPUT_BUFFER.lock().unwrap().clear();
        let timeout = None;
        let stream_config = StreamConfig {
            channels: device_channel,
            sample_rate: config.sample_rate(),
            buffer_size: BufferSize::Default,
        };
        let stream = device.build_input_stream(
            &stream_config,
            move |data: &[T], _: &InputCallbackInfo| {
                let buffer: Vec<f32> = data.iter().map(|s| T::to_sample(*s)).collect();
                let mut lock = INPUT_BUFFER.lock().unwrap();
                lock.extend(buffer);
                while lock.len() >= rechannel_len {
                    let frame: Vec<f32> = lock.drain(0..rechannel_len).collect();
                    send(
                        frame,
                        sample_rate_0,
                        sample_rate,
                        device_channel,
                        encode_channel as _,
                        &mut encoder,
                        &sp,
                    );
                }
            },
            err_fn,
            timeout,
        )?;
        Ok(stream)
    }
}

fn create_format_msg(sample_rate: u32, channels: u16) -> Message {
    let format = AudioFormat {
        sample_rate,
        channels: channels as _,
        ..Default::default()
    };
    let mut misc = Misc::new();
    misc.set_audio_format(format);
    let mut msg = Message::new();
    msg.set_misc(misc);
    msg
}

// use AUDIO_ZERO_COUNT for the Noise(Zero) Gate Attack Time
// every audio data length is set to 480
// MAX_AUDIO_ZERO_COUNT=800 is similar as Gate Attack Time 3~5s(Linux) || 6~8s(Windows)
const MAX_AUDIO_ZERO_COUNT: u16 = 800;
static mut AUDIO_ZERO_COUNT: u16 = 0;

fn send_f32(data: &[f32], encoder: &mut Encoder, sp: &GenericService) -> usize {
    #[cfg(not(target_os = "android"))]
    if data.iter().filter(|x| **x != 0.).next().is_some() {
        unsafe {
            AUDIO_ZERO_COUNT = 0;
        }
    } else {
        unsafe {
            if AUDIO_ZERO_COUNT > MAX_AUDIO_ZERO_COUNT {
                if AUDIO_ZERO_COUNT == MAX_AUDIO_ZERO_COUNT + 1 {
                    log::debug!("Audio Zero Gate Attack");
                    AUDIO_ZERO_COUNT += 1;
                }
                return 0;
            }
            AUDIO_ZERO_COUNT += 1;
        }
    }
    #[cfg(target_os = "android")]
    {
        // the permitted opus data size are 120, 240, 480, 960, 1920, and 2880
        // if data size is bigger than BATCH_SIZE, AND is an integer multiple of BATCH_SIZE
        // then upload in batches
        const BATCH_SIZE: usize = 960;
        let input_size = data.len();
        let mut sent = 0;
        if input_size > BATCH_SIZE && input_size % BATCH_SIZE == 0 {
            let n = input_size / BATCH_SIZE;
            for i in 0..n {
                match encoder
                    .encode_vec_float(&data[i * BATCH_SIZE..(i + 1) * BATCH_SIZE], BATCH_SIZE)
                {
                    Ok(data) => {
                        let mut msg_out = Message::new();
                        msg_out.set_audio_frame(AudioFrame {
                            data: data.into(),
                            ..Default::default()
                        });
                        sp.send(msg_out);
                        sent += 1;
                    }
                    Err(err) => {
                        log::warn!(
                            "MDM-AudioServiceEncodeFailed batch={} input_size={} error={}",
                            i,
                            input_size,
                            err
                        );
                    }
                }
            }
        } else {
            log::warn!("MDM-AudioServiceInvalidInputSize input_size={}", input_size);
            return 0;
        }
        return sent;
    }

    #[cfg(not(target_os = "android"))]
    return match encoder.encode_vec_float(data, data.len() * 6) {
        Ok(data) => {
            let mut msg_out = Message::new();
            msg_out.set_audio_frame(AudioFrame {
                data: data.into(),
                ..Default::default()
            });
            sp.send(msg_out);
            1
        }
        Err(_) => 0,
    };
}
