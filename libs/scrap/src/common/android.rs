use crate::android::ffi::*;
use crate::{Frame, Pixfmt};
use lazy_static::lazy_static;
use serde_json::Value;
use std::collections::HashMap;
use std::sync::Mutex;
use std::{io, time::Duration};

lazy_static! {
   pub(crate)  static ref SCREEN_SIZE: Mutex<(u16, u16, u16)> = Mutex::new((0, 0, 0)); // (width, height, scale)
}

pub struct Capturer {
    display: Display,
    capture_width: usize,
    capture_height: usize,
    rgba: Vec<u8>,
    saved_raw_data: Vec<u8>, // for faster compare and copy
    rgba_scaled: Vec<u8>,    // downscaled buffer
}

fn get_scale() -> f64 {
    if call_main_service_get_by_name("capture_source")
        .map(|source| source == "mdm_screenrecord")
        .unwrap_or(false)
    {
        return 1.0;
    }
    hbb_common::config::Config::get_option("mdm-scale-resolution-down-by")
        .trim()
        .parse::<f64>()
        .unwrap_or(1.0)
        .max(1.0)
}

fn is_managed_screenrecord() -> bool {
    call_main_service_get_by_name("capture_source")
        .map(|source| source == "mdm_screenrecord")
        .unwrap_or(false)
}

fn get_capture_size() -> Option<(usize, usize)> {
    let value = call_main_service_get_by_name("capture_size").ok()?;
    let json = serde_json::from_str::<HashMap<String, Value>>(&value).ok()?;
    let width = json.get("width")?.as_u64()? as usize;
    let height = json.get("height")?.as_u64()? as usize;
    (width > 0 && height > 0).then_some((width, height))
}

pub fn expected_capture_size(display: &Display) -> (usize, usize) {
    if is_managed_screenrecord() {
        return get_capture_size().unwrap_or((display.width(), display.height()));
    }
    let scale = get_scale();
    if scale <= 1.001 {
        return (display.width(), display.height());
    }
    let width = (((display.width() as f64 / scale) as usize) / 2 * 2).max(16);
    let height = (((display.height() as f64 / scale) as usize) / 2 * 2).max(16);
    (width, height)
}

fn downscale_rgba(
    src: &[u8],
    src_w: usize,
    src_h: usize,
    dst: &mut Vec<u8>,
    dst_w: usize,
    dst_h: usize,
) {
    dst.resize(dst_w * dst_h * 4, 0);
    for dy in 0..dst_h {
        let sy = (dy * src_h) / dst_h;
        let src_row_offset = sy * src_w * 4;
        let dst_row_offset = dy * dst_w * 4;
        for dx in 0..dst_w {
            let sx = (dx * src_w) / dst_w;
            let src_pixel_offset = src_row_offset + sx * 4;
            let dst_pixel_offset = dst_row_offset + dx * 4;
            dst[dst_pixel_offset..dst_pixel_offset + 4]
                .copy_from_slice(&src[src_pixel_offset..src_pixel_offset + 4]);
        }
    }
}

impl Capturer {
    pub fn new(display: Display) -> io::Result<Capturer> {
        let (capture_width, capture_height) = expected_capture_size(&display);
        Ok(Capturer {
            display,
            capture_width,
            capture_height,
            rgba: Vec::new(),
            saved_raw_data: Vec::new(),
            rgba_scaled: Vec::new(),
        })
    }

    pub fn width(&self) -> usize {
        self.capture_width
    }

    pub fn height(&self) -> usize {
        self.capture_height
    }
}

impl crate::TraitCapturer for Capturer {
    fn frame<'a>(&'a mut self, _timeout: Duration) -> io::Result<Frame<'a>> {
        if get_video_raw(&mut self.rgba, &mut self.saved_raw_data).is_some() {
            if is_managed_screenrecord() {
                if let Some((width, height)) = get_capture_size() {
                    let expected_len = width * height * 4;
                    if self.rgba.len() == expected_len
                        && (self.capture_width != width || self.capture_height != height)
                    {
                        self.capture_width = width;
                        self.capture_height = height;
                        log::info!(
                            "android capturer synchronized capture frame size to {}x{} bytes={}",
                            width,
                            height,
                            self.rgba.len()
                        );
                    }
                }
            }
            let scale = get_scale();
            if scale > 1.001 {
                let orig_w = self.display.width() as usize;
                let orig_h = self.display.height() as usize;
                let dst_w = self.capture_width;
                let dst_h = self.capture_height;
                if self.rgba.len() >= orig_w * orig_h * 4 {
                    downscale_rgba(
                        &self.rgba,
                        orig_w,
                        orig_h,
                        &mut self.rgba_scaled,
                        dst_w,
                        dst_h,
                    );
                    Ok(Frame::PixelBuffer(PixelBuffer::new(
                        &self.rgba_scaled,
                        dst_w,
                        dst_h,
                    )))
                } else {
                    Ok(Frame::PixelBuffer(PixelBuffer::new(&self.rgba, orig_w, orig_h)))
                }
            } else {
                Ok(Frame::PixelBuffer(PixelBuffer::new(
                    &self.rgba,
                    self.capture_width,
                    self.capture_height,
                )))
            }
        } else {
            return Err(io::ErrorKind::WouldBlock.into());
        }
    }
}

pub struct PixelBuffer<'a> {
    data: &'a [u8],
    width: usize,
    height: usize,
    stride: Vec<usize>,
}

impl<'a> PixelBuffer<'a> {
    pub fn new(data: &'a [u8], width: usize, height: usize) -> Self {
        let stride0 = data.len() / height;
        let mut stride = Vec::new();
        stride.push(stride0);
        PixelBuffer {
            data,
            width,
            height,
            stride,
        }
    }
}

impl<'a> crate::TraitPixelBuffer for PixelBuffer<'a> {
    fn data(&self) -> &[u8] {
        self.data
    }

    fn width(&self) -> usize {
        self.width
    }

    fn height(&self) -> usize {
        self.height
    }

    fn stride(&self) -> Vec<usize> {
        self.stride.clone()
    }

    fn pixfmt(&self) -> Pixfmt {
        Pixfmt::RGBA
    }
}

pub struct Display {
    default: bool,
    rect: Rect,
}

#[derive(Copy, Clone, Debug, Hash, Eq, PartialEq)]
struct Rect {
    pub x: i16,
    pub y: i16,
    pub w: u16,
    pub h: u16,
}

impl Display {
    pub fn primary() -> io::Result<Display> {
        let mut size = SCREEN_SIZE.lock().unwrap();
        if size.0 == 0 || size.1 == 0 {
            *size = get_size().unwrap_or_default();
        }
        Ok(Display {
            default: true,
            rect: Rect {
                x: 0,
                y: 0,
                w: size.0,
                h: size.1,
            },
        })
    }

    pub fn all() -> io::Result<Vec<Display>> {
        Ok(vec![Display::primary()?])
    }

    pub fn width(&self) -> usize {
        self.rect.w as usize
    }

    pub fn height(&self) -> usize {
        self.rect.h as usize
    }

    pub fn origin(&self) -> (i32, i32) {
        let r = self.rect;
        (r.x as _, r.y as _)
    }

    pub fn is_online(&self) -> bool {
        true
    }

    pub fn is_primary(&self) -> bool {
        self.default
    }

    pub fn name(&self) -> String {
        "Android".into()
    }

    pub fn refresh_size() {
        let mut size = SCREEN_SIZE.lock().unwrap();
        *size = get_size().unwrap_or_default();
    }

    // Big android screen size will be shrinked, to improve performance when screen-capturing and encoding
    // e.g 2280x1080 size will be set to 1140x540, and `scale` is 2
    // need to multiply by `4` (2*2) when compute the bitrate
    pub fn fix_quality() -> u16 {
        let scale = SCREEN_SIZE.lock().unwrap().2;
        if scale <= 0 {
            1
        } else {
            scale * scale
        }
    }
}

fn get_size() -> Option<(u16, u16, u16)> {
    let res = call_main_service_get_by_name("screen_size").ok()?;
    if let Ok(json) = serde_json::from_str::<HashMap<String, Value>>(&res) {
        if let (Some(Value::Number(w)), Some(Value::Number(h)), Some(Value::Number(scale))) =
            (json.get("width"), json.get("height"), json.get("scale"))
        {
            let w = w.as_i64()? as _;
            let h = h.as_i64()? as _;
            let scale = scale.as_i64()? as _;
            return Some((w, h, scale));
        }
    }
    None
}

pub fn is_start() -> Option<bool> {
    let res = call_main_service_get_by_name("is_start").ok()?;
    Some(res == "true")
}
