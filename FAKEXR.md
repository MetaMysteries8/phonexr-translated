# FakeXR controller mode

Open **PhoneXR → Settings → Controls → FakeXR** and pair a conventional Bluetooth or USB gamepad. Enable the PhoneXR Joy-Con accessibility service to let PhoneXR read its buttons while a VR game is in front. FakeXR is off by default.

- **Sticks move hands:** each stick moves one virtual hand on a plane in front of the viewer. Stick values are not sent to the game for locomotion.
- **Sticks for movement:** both virtual hands rest around the gamepad's shared position, and both sticks continue to reach the VR game. The game's own bindings decide what they do.
- **Track marker 0 (Full edition):** print `markers/joycon_markers_A4.pdf` at actual size and attach marker **0** to the center of the gamepad where the phone's outward camera can see it. Both hands follow that marker with a small side offset. When the marker is lost, both hands return to the fixed forward pose. Lite does not include marker tracking.

A standard gamepad reports buttons and sticks, not its location. Marker tracking provides an approximate shared pose; it does not track either real hand independently. Some games need separate tracked controllers and may reject or ignore this input.

Games already classified as OpenXR, Quest/Gear VR, or Daydream/Cardboard are launched through their declared immersive activity when present. Original headset builds may still need PhoneXR's existing patch or runtime path and may not run on a phone. Ordinary Android games use PhoneXR's existing Cinema virtual display or VR app windows; they remain flat games on a VR screen rather than becoming native stereoscopic VR.
