use std::io::{self, BufRead, BufWriter, Write};
use std::sync::Arc;

use anyhow::{Context as _, Result, ensure};
use gpui::{prelude::*, *};

struct Probe {
    focus: FocusHandle,
    pointer: Point<Pixels>,
    host_focused: bool,
    blue: u32,
}

impl Render for Probe {
    fn render(&mut self, _: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let accent = if self.host_focused {
            0x50dc50
        } else {
            0x3c8cdc
        };
        div()
            .size_full()
            .bg(rgb(0x1e1900 | self.blue))
            .track_focus(&self.focus)
            .on_mouse_move(cx.listener(|view, event: &MouseMoveEvent, _, cx| {
                view.pointer = event.position;
                cx.notify();
            }))
            .on_key_down(cx.listener(|view, event: &KeyDownEvent, _, cx| {
                if let Some(character) = event.keystroke.key.chars().next() {
                    view.blue = 35 + u32::from(character) % 150;
                    cx.notify();
                }
            }))
            .child(div().w_full().h(px(8.)).bg(rgb(accent)))
            .child(
                div()
                    .absolute()
                    .left(self.pointer.x - px(5.))
                    .top(self.pointer.y - px(5.))
                    .size(px(11.))
                    .bg(rgb(0xffffff)),
            )
    }
}

struct Request {
    width: u32,
    height: u32,
    x: i32,
    y: i32,
    focused: bool,
    key: Option<char>,
}

impl Request {
    fn parse(line: &str) -> Result<Self> {
        let fields = line
            .split_ascii_whitespace()
            .map(str::parse::<i32>)
            .collect::<Result<Vec<_>, _>>()?;
        let &[width, height, x, y, focused, key] = fields.as_slice() else {
            anyhow::bail!("Expected six frame request fields");
        };
        ensure!(
            (1..=960).contains(&width) && (1..=540).contains(&height),
            "Invalid frame size"
        );
        ensure!((0..=1).contains(&focused), "Invalid focus state");
        let key = char::from_u32(u32::try_from(key)?).context("Invalid key code point")?;
        Ok(Self {
            width: width as u32,
            height: height as u32,
            x,
            y,
            focused: focused == 1,
            key: (key != '\0').then_some(key),
        })
    }
}

fn main() -> Result<()> {
    let mut cx = HeadlessAppContext::with_platform(
        gpui_platform::current_platform(true).text_system(),
        Arc::new(()),
        gpui_platform::current_headless_renderer,
    );
    let window = cx.open_window(size(px(64.), px(48.)), |_, cx| {
        cx.new(|cx| Probe {
            focus: cx.focus_handle(),
            pointer: point(px(-10.), px(-10.)),
            host_focused: false,
            blue: 35,
        })
    })?;
    let mut out = BufWriter::new(io::stdout().lock());
    let mut last_key = None;
    for line in io::stdin().lock().lines() {
        let request = Request::parse(&line?)?;
        window.update(&mut cx, |view, window, cx| {
            let size = size(px(request.width as f32), px(request.height as f32));
            if window.viewport_size() != size {
                window.resize(size);
                window.bounds_changed(cx);
            }
            view.host_focused = request.focused;
            view.focus.focus(window, cx);
            cx.notify();
        })?;
        // Dispatch outside the view update so the event handlers can mutate it.
        let image = cx.update_window(window.into(), |_, window, cx| {
            // Draw once to register the layout's focus and key handlers.
            let _ = window.draw(cx);
            window.simulate_mouse_move(point(px(request.x as f32), px(request.y as f32)), cx);
            if let Some(key) = request.key.filter(|key| Some(*key) != last_key) {
                let input = key.to_string();
                window.dispatch_keystroke(
                    Keystroke {
                        modifiers: Modifiers::default(),
                        key: input.clone(),
                        key_char: Some(input),
                    },
                    cx,
                );
            }
            let _ = window.draw(cx);
            window.render_to_image()
        })??;
        last_key = request.key;
        ensure!(
            image.width() >= request.width && image.height() >= request.height,
            "GPUI returned a smaller frame than requested"
        );
        // Headless GPUI can render at 2x. Sample one physical pixel per logical pixel.
        let mut row = Vec::with_capacity(request.width as usize * 4);
        for y in 0..request.height {
            row.clear();
            for x in 0..request.width {
                let [r, g, b, a] = image
                    .get_pixel(
                        x * image.width() / request.width,
                        y * image.height() / request.height,
                    )
                    .0;
                row.extend([b, g, r, a]);
            }
            out.write_all(&row)?;
        }
        out.flush()?;
    }
    Ok(())
}
