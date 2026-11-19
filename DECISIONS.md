# Decisions

## 2026-11-19

- Fixed the `Settings$NameValueCache` hook ABI in `HideDevOptionsScript.kt` from a boolean result to the `String` result expected by the 2.0.6.2 patch contract. The injected block now uses `move-result-object` and returns the hook value directly.
- Added `.github/workflows/build.yml` to build debug and release APKs on pushes, pull requests, and manual dispatch, then upload both APK outputs as workflow artifacts.
- Kept release signing out of CI because the repository has no configured signing secret or signing configuration. The release artifact is therefore unsigned; no key material is added to the repository.
- Kept the existing app version and broader patch scope unchanged. The request identified a concrete ABI incompatibility; expanding the patch surface without source fixtures or device validation would be speculative.
