# Magazines

`com.wf.wflib.magazine`: MA guns + CA vehicle weapons.

- `PodTubes` equality = item + components per tube (`ItemStack` has none) => pod stacks compare by content.
- `AmmoCodecs`: registry-free => JUnit-loadable; `AmmoItems` statics need a bootstrap.
- `pod_gmlrs` tube centres = CA `m142` station sockets about their centroid.
- Gap: `LoadPreset` mix UI is MA's (`LoadPresetScreen`, main-hand any `Magazine`); WFLib item use fills single-kind.
