# Third-party notices

SkyWindow embeds the following third-party software.

## Mojang Brigadier

Source: https://github.com/Mojang/brigadier (command parser & dispatcher designed for Minecraft:
Java Edition). Vendored sources under `src/main/java/fr/buildtheearth/skywindow/brigadier/`
(package relocated from `com.mojang.brigadier` so the extension never collides with a host
classpath; per-file MIT headers preserved). SkyWindow uses Brigadier's parser as the *primary*
command-shape oracle (`CommandTreeIndex`) so command parsing runs on the exact engine Minecraft
itself uses - literal-first matching, argument fallback with backtracking, redirect aliases and
command-syntax errors included.

License: MIT (see below). Copyright (c) Microsoft Corporation. All rights reserved.

```
MIT License

Copyright (c) Microsoft Corporation. All rights reserved.

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

```
