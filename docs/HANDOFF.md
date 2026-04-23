# Handoff Notes

## What has been completed

- A standalone Android project was created at `foodocr_app`
- CameraX preview and analysis are working
- ML Kit Chinese OCR is integrated
- The app now separates manufacture-date voting and expiry-date voting
- The parser supports multiple date formats, including compact numeric styles
- Adjacent-line grouping was added so labels and dates do not have to be on the exact same line
- A basic image-quality scorer and fallback enhancement pass were added
- Debug and release APKs build successfully on the current machine

## Important files

- `app/src/main/java/com/example/foodocr/MainActivity.kt`
- `app/src/main/java/com/example/foodocr/FoodDateAnalyzer.kt`
- `app/src/main/res/layout/activity_main.xml`
- `app/build.gradle.kts`

## Current heuristics

- Label keywords are split into two buckets:
  - manufacture-like
  - expiry-like
- OCR text is normalized to reduce common mistakes such as `O -> 0`
- Dates are parsed from:
  - separated numeric forms
  - Chinese year/month/day forms
  - compact numeric strings
- If no explicit role is found, the app may infer:
  - earlier date -> manufacture
  - later date -> expiry

## What still needs real-world tuning

- Reflection-heavy packaging
- Wrinkled or curved plastic bags
- Faint inkjet and dot-matrix printing
- Cases with multiple unrelated numbers near the date
- Cases where MFG and EXP appear in very different layouts

## Best next step on another machine

Use real package photos or a connected device and test these scenarios:

1. `MFG` and date on the same line
2. `EXP` label above or below the date
3. Date only, no label
4. Compact numeric date like `20261121`
5. Month-first compact date like `11212026`
6. Strong glare and low contrast
7. Two dates shown together on one label

For each failure case, save:

- original image
- expected output
- actual output
- whether the label was present
- whether reflection or blur was involved

## Suggested future improvements

- Add a still-image import/test mode inside the app
- Add a debug overlay showing OCR lines and chosen candidates
- Add confidence logging for role scoring and date voting
- Add ROI cropping before OCR
- Add a second OCR pass with targeted crop regions
- If needed, integrate OpenCV for adaptive thresholding and denoising

## Build notes

- The project uses Gradle wrapper, so Android Studio can sync directly
- `local.properties` is intentionally ignored because it is machine-specific
- This repo should not commit `build/` outputs
