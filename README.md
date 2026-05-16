# fluttermocklocation (patched fork)

Fork of `fluttermocklocation 0.0.5` (https://github.com/dariocavada/fluttermocklocation)
with stability fixes for continuous mock location updates.

## Why this fork

The upstream 0.0.5 plugin calls `LocationManager.addTestProvider(GPS_PROVIDER, ...)`
on **every** `updateMockLocation()` call. After the first call Android raises
`IllegalArgumentException("GPS_PROVIDER")` because the provider already exists.
The plugin catches the exception silently — and skips the subsequent
`setTestProviderLocation()` call, so the mock position freezes at whatever was
set on the very first push.

In addition the plugin posts a new periodic `Handler` runnable on every call
without removing the previous one, so the loop accumulates linearly with the
push frequency.

## Patches applied

1. **`addTestProvider` runs exactly once** per process lifetime. A
   `providerInitialized` flag gates subsequent calls.
2. **`removeTestProvider` is attempted first** during initialization to clear
   stale provider state from a previous run of the app.
3. **Subsequent updates only call `setTestProviderLocation`** — never
   `addTestProvider` again.
4. **The periodic re-push handler is cleared with `removeCallbacks` before
   each `postDelayed`**, so accumulated runnables are no longer possible.
5. **Accuracy is configurable** via a new `accuracy` argument on the method
   channel (float, meters). Falls back to `1.0` if missing. The upstream
   hard-coded `accuracy = 5f`.
6. **`SecurityException` and `IllegalArgumentException` are now logged**
   to `adb logcat` (tag `FluttermockLoc`) so the Flutter side can see why
   a mock attempt failed.
7. **New `stopMockLocation` method** cleanly tears the provider down.

The Dart-side API (`Fluttermocklocation().updateMockLocation(lat, lon, ...)`)
is unchanged — calling apps don't need to touch their code. The optional
`accuracy` argument can be passed through the method channel directly if
needed; absent it the plugin uses `1.0`.

## License

Same as upstream — see `LICENSE`.
