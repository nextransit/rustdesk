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
    rgba: Vec<u8>,
    saved_raw_data: Vec<u8>, // for faster compare and copy
    rgba_scaled: Vec<u8>, // downscaled buffer
}

fn get_scale() -> f64 {
    hbb_common::config::Config::get_option("mdm-scale-resolution-down-by")
        .trim()
        .parse::<f64>()
        .unwrap_or(1.0)
        .max(1.0)
}

fn downscale_rgba(src: &[u8], src_w: usize, src_h: usize, dst: &mut Vec<u8>, dst_w: usize, dst_h: usize) {
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
        Ok(Capturer {
            display,
            rgba: Vec::new(),
            saved_raw_data: Vec::new(),
            rgba_scaled: Vec::new(),
        })
    }

    pub fn width(&self) -> usize {
        let scale = get_scale();
        if scale > 1.001 {
            let mut w = (self.display.width() as f64 / scale) as usize;
            w = (w / 2) * 2;
            w.max(16)
        } else {
            self.display.width() as usize
        }
    }

    pub fn height(&self) -> usize {
        let scale = get_scale();
        if scale > 1.001 {
            let mut h = (self.display.height() as f64 / scale) as usize;
            h = (h / 2) * 2;
            h.max(16)
        } else {
            self.display.height() as usize
        }
    }
}

impl crate::TraitCapturer for Capturer {
    fn frame<'a>(&'a mut self, _timeout: Duration) -> io::Result<Frame<'a>> {
        if get_video_raw(&mut self.rgba, &mut self.saved_raw_data).is_some() {
            if let Some((width, height, _)) = get_size() {
                let expected_len = width as usize * height as usize * 4;
                if self.rgba.len() == expected_len
                    && (self.display.rect.w != width || self.display.rect.h != height)
                {
                    self.display.rect.w = width;
                    self.display.rect.h = height;
                    let mut screen_size = SCREEN_SIZE.lock().unwrap();
                    let scale = screen_size.2;
                    *screen_size = (width, height, scale);
                    log::info!(
                        "android capturer synchronized raw frame size to {}x{} bytes={}",
                        width,
                        height,
                        self.rgba.len()
                    );
                }
            }
            let scale = get_scale();
            if scale > 1.001 {
                let orig_w = self.display.width() as usize;
                let orig_h = self.display.height() as usize;
                let dst_w = self.width();
                let dst_h = self.height();
                if self.rgba.len() >= orig_w * orig_h * 4 {
                    downscale_rgba(&self.rgba, orig_w, orig_h, &mut self.rgba_scaled, dst_w, dst_h);
                    Ok(Frame::PixelBuffer(PixelBuffer::new(
                        &self.rgba_scaled,
                        dst_w,
                        dst_h,
                    )))
                } else {
                    Ok(Frame::PixelBuffer(PixelBuffer::new(
                        &self.rgba,
                        orig_w,
                        orig_h,
                    )))
                }
            } else {
                Ok(Frame::PixelBuffer(PixelBuffer::new(
                    &self.rgba,
                    self.width(),
                    self.height(),
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
