# UI widgets

HUD, feed OSD, texture surfaces, probe and map painter draw on the GemRender `Canvas` (GemRender INTEGRATION §11). Layouts = trees of `WidgetDef` records compiled against named signals, built in Java (`Ui.*`) or JSON (§96); both compile to the same tree.

## 90. Pieces (`ui/`)

| type | role |
|---|---|
| `Subject<S>` | named source object (`vehicle`, `player`, ...); first segment of every signal name. `register(name, type[, fallback])` |
| `Signals` | `num` / `bool` / `choice(names)` / `text(maxLength)` / `items(itemSubject)` per subject; name unique, duplicate => throws |
| `UiContext` | instance's subjects per frame; null => subject fallback, else absent |
| `WidgetDef` | immutable description; `create(Binder)` compiles props, returns `Widget` |
| `Binder` | `num` / `bool` / `color` / `text` props, `items`, `subject`, `enterScope`. First instance compiles; later instances replay in call order |
| `UiLayout` | id + root + `reference` (scaling, below); `instantiate()` compiles once per layout |
| `UiInstance` | values, state, widgets; `context(..)`, `render(canvas, box, partialTick)`; `current()` false after a reload re-resolved its id => re-instantiate |
| `UiLayouts` | by id: `register` (Java) + JSON resources; resource overrides Java while loaded |
| `UiHud` | layers `wflib:hud_back` (below all) + `wflib:hud` (below chat); `addSource(layer, out -> out.add(instance))` per frame. Hidden GUI => skipped |
| `UiSurfaces` | `request(key, layout, context, px, py, units, maxFps, clear)` per frame seen => texture id; redraw at frame start; freed 120 frames unrequested; premultiplied alpha |
| `ScreenProjection` | world => GUI px of the last main view frame, no allocation |

Scaling: `reference > 0` (default 270) => box = `reference / zoom` layout units tall at any resolution; 1 unit = 1 GUI px at 1080p auto scale. HUD zoom = GUI scale / auto GUI scale (scale 2 at 1080p => half size); surfaces zoom 1. `0` / `absolute()` => host units (GUI px; surface units). Absolute inside relative: `Ui.absolute(child)` / JSON `absolute` subtree, `gu` / `px` units. Host coordinates (`ScreenProjection`, GUI-space aim points) => `ev.fromHost(..)`. Vanilla drawing under the canvas transform: `canvas.applyTo(pose)`.

Built-in subjects: `ui` (`width`, `height` (layout units), `scale` (host units per unit), `px` (framebuffer pixel in units), `partial`, `gui_scale`, `hide_gui`; `ui.time`, `ui.dt` = fixed slots, seconds), `player`, `camera`, `world` (`hour`, `minute`, `day_time`, `game_time`), `feed` (OSD), `tv`. Every subject: `<name>.present`. Inside `repeat`: item subject, `index`, `count`.

Missing subject => NUM NaN, BOOL false, TEXT empty, list empty.

## 91. Expressions (`ui/expr/`)

- Types NUM / BOOL; arithmetic on BOOL, BOOL condition from NUM => compile error naming layout + expression.
- Operators `+ - * / % ^`, `< <= > >= == !=`, `&& || !`, `?:`. `%` floored. `-2^3` = -8.
- `?:` lowest precedence => Java-spliced fragments containing one need parens: `x + " + 8"` with `x = "a ? b : c"` adds 8 to `c` only.
- Literals: numbers, `#RRGGBB` (opaque), `#AARRGGBB`, `true`, `false`, `pi`. Units: `3px` (framebuffer px), `2gu` (host / GUI px), `50vw`, `50vh`. Choice value: `subject.signal.value` => its index.
- Functions: `abs sign floor ceil round trunc fract sqrt sin cos tan asin acos atan exp log deg rad isnan atan2 pow hypot min max(2+) mod step nz alpha clamp lerp invlerp smoothstep wrap rgb mixc rgba remap`; `blink(period)` (time); `smooth(x, tau_s)`, `rate(x)`, `since(cond)` (s held, 0 when false), `age(x)` (s since value changed) keep state => refused inside `repeat`. State advances only while evaluated: inside a hidden `when` branch it freezes => latch on a stand / reset = Java signal.
- Compiled: ASM => hidden class per non-constant expression (`ExprCompiler`); constant subtrees folded. `-Dwflib.ui.interpret=true` => tree interpreter (`Node.eval`), the reference semantics; `ExprTest` diffs both over 3000 random trees, NaN cases included.

## 92. Templates (`TextProp`)

`"{expr|fmt} literal {text.signal|upper} {choice.signal|tr:key.prefix.} {key:key.mapping.name} {tr:key} {tr:key(arg, arg)}"`

| fmt | |
|---|---|
| none | 0 decimals |
| `[+][0][width][.decimals]` | `|.1`, `|03`, `|+.1`, `|5` (space pad) |
| `[0][width]X` | upper hex |
| `clock` / `clockh` | `mm:ss` / `hh:mm:ss` |
| `upper` / `lower` | text parts |
| `tr:prefix` / `upper` / none | choice: translation of prefix + value name / name; table built at bind |

- `tr:key(args)`: translation's `%s` / `%d` / `%n$s` filled by the parts in order; translated once at bind.
- Buffers sized at bind: literals + number widths + text signals' declared `maxLength`; longer text cut.
- Format separator = last single `|` (`||` = OR).
- `key:` = bound key's display name, rebuilt on rebind, max 32 chars.

## 93. Widgets (`ui/widget/`, `Ui` shorthands)

| widget | |
|---|---|
| `Stack` | children share the box |
| `Flow` (`row`, `column`) | gap, cross-axis align; zero-size children take no gap |
| `Anchor` | 9-point pin + inward margin + offset exprs |
| `Place` (`place`, `at`) | child pivot at (x, y) exprs; size 0 |
| `Transform` | translate, rotate (deg, clockwise), uniform scale about a pivot; layout ignores it |
| `Box` | background, inner border, radius, padding, min size |
| `Sized` | fixed size exprs, align, optional clip |
| `When` | then / else; hidden => size 0 |
| `Repeat` | list signal; row, column or `columns`-wide grid |
| `Text` | template, colour, shadow, scale, `maxWidth` (`..` ellipsis) |
| `Rect`, `Bar`, `Image`, `Line`, `Arc`, `Poly` | shapes; `Bar`: bipolar, marker, divisions, inset |
| `Tape` | moving scale (compass, heading, altitude); wrap, named marks, label scale/pad, major colour |
| `Ladder` | pitch ladder; wrap in `Transform` (roll) + clipping `Sized` |
| `Dial` | dotted quadrant: minor dots, major / accent ticks, band, needles; degrees CCW from +x |
| `Feed` | live camera feed by id; requests it at the drawn pixel width => never gate it on the feed being drawable (never requested => never drawable) |
| `Button` | `Box` + click `UiAction`; `hover` / `pressed` backgrounds; `enabled` false => no hit |
| `ListView` (`list`) | virtualised fixed-row list over a list signal; wheel scroll; scroll thumb |
| `ScrollBox` (`scroll`) | clip + wheel-scrolled child |
| `Slider` | 0..1 track; press/drag/wheel => `UiSetter` |
| `DragArea` (`drag`) | drag deltas + wheel => `UiDrag`; child's interactive widgets sit above it |

Native widget = any `WidgetDef`; read subjects through `binder.subject(..)` => `SubjectRef.get(ev)`. Vanilla drawing (items, entities, shaders): `ev.graphics` (HUD only) between `ev.canvas.suspend()` / `resume()`.

### Input (`ui/input/`)

- Opt-in per instance: `UiInstance.input()` => `UiInput`; host feeds `pointer(x, y, buttons)` / `wheel` in render-box units. Null `ev.input` => interactive widgets draw, record nothing.
- Immediate mode: widgets `hit(..)` boxes while drawing; next render's `dispatch` resolves against them => 1 frame stale. Topmost = last recorded => container hits before its children.
- Hit identity = (owner, item), item = innermost repeat element (`binder.itemSlot()` -> `ev.item(slot)`), `equals` across frames.
- Any canvas transform/clip around interactive children MUST be mirrored on `ev.input` (`Transform`, `Sized` clip, `ListView`, `ScrollBox` do); `Text`/`Image` push only for their own glyphs.
- Capture: press => drags + release go to the pressed hit even outside it; `release(.., inside)` = click.
- Wheel: topmost-down until a `wheel` returns true.
- JSON names handlers by id: `UiActions.register(id, UiAction|UiSetter|UiDrag)` at client setup; `"action"`, `"set"`, `"handler"`.
- Keyboard (no Screen hosts): `UiKeyboard.focus(Target)` => every key / char to it, none to key mappings (`MixinKeyboardHandler`; Esc included), held keys released. Any new primary press blurs; screen open => blur. `TextField` + `TextLine` model (edit keys, history, Ctrl+V); code-only.

## 94. Hosts

- HUD: CombinedAssault (JSON presets `combinedassault:vehicle|rotary_wing|fixed_wing|ship|scope|hit`, `client/hud/CaHudSource`), MineAssault (`mineassault:gun`, Java), wflib TV (`wflib:tv`, Java), `/wfui demo` (Java).
- Feed OSD: `wflib:camera_osd`, absolute 320x180 units (relative default 270 => drawn at 67%), `Canvas.begin(fbo, .., topRowFirst = true)` over the feed picture.
- Probe panel, nodes, job bar; map painter + `MapTool.render(Canvas, MapView)` (Xaero, JourneyMap): canvas; 3D probe elements through vanilla between `suspend`/`resume`.

## 96. JSON layouts (`ui/json/`)

Files (client resources, pack-overridable, F3+T): `assets/<ns>/wf_ui/layouts/<path>.json` => layout `<ns>:<path>`; `wf_ui/components/<path>.json` => widget type `<ns>:<path>`; `wf_ui/palettes/<path>.json` => flat constant map.

```json
{"scaling": "relative", "reference": 270, "use": ["ns:palette"], "constants": {"green": "#dd00ff00"},
 "root": {"type": "column", "gap": 2, "children": [
   {"type": "text", "text": "{vehicle.speed} km/h", "color": "$green", "shadow": true},
   {"type": "ns:console_row", "y": 24, "key": "ui.ns.speed", "value": {"type": "text", "text": "{ship.knots|.1}"}}]}}
```

Component: `{"use", "constants", "params": {"name": "num" | {"type": .., "default": ..}}, "root"}`; call = object with `type` = its id, params as keys.

| param type | value |
|---|---|
| `num` / `bool` / `color` | expression |
| `text` | template fragment, inserted raw |
| `key` | translation / resource key or subject name, inserted raw (`{$weapon.ammo}`) |
| `widget` / `widgets` | widget / array; `"child": "$p"`, `"children": ["$items", ..]` splices |

- `$name` => param / constant text; parenthesised unless one token => argument stays one operand (`$y + 8`). Not rescanned => args resolve in caller scope. `$$` => `$`.
- Constants are expressions: `"NO DATA"` would insert `(NO DATA)` => text goes through `text` params.
- Constant props (`gap`, `w` of `bar`, ...): JSON literal or constant expression (`"$gap * 2"`); units not allowed.
- Widget types: bare name = `wflib:<name>`, props = record components in snake_case (`max_width`, `label_offset`); `absolute` = child in host units. Java widgets: `UiWidgetTypes.register(id, props -> ..)` at client setup; `JsonProps` reads + rejects unknown keys.
- Load: parse on reload, expand + compile first frame after the loading overlay (types and signals registered). Per layout id highest pack that expands and compiles wins, else previous good, else Java registration. Errors: log `[wf_ui]` with file + JSON pointer, chat in dev. Self-including component / depth > 32 => error.
- JSON cannot: add data (only registered signals), keep state beyond the stateful built-ins, repeat over anything but item signals, handle input except through registered handlers, draw custom geometry, place by measured text. Those = Java widget (`WidgetDef`), usable from JSON once registered.
- Runtime cost = Java-built tree: expansion inlines components, `Located` wrapper only acts at bind.

