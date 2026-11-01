# Screenshot break limit on HyperOS

Module Xposed/LSPosed cho **HyperOS / MIUI** (app `com.miui.screenshot`):
bo gioi han chieu cao **~20.000 pixel** cua anh dai (scroll screenshot) va tang toc do cuon, tat ca **co the tuy chinh trong module**.

## Feature

| Feature | Noi hook trong c.apk |
|---|---|
| Bo gioi han chieu cao anh dai | `com.miui.screenshot.accessibility.q#I(Context)` - field static int `q.r = screenHeight * 8` |
| Khong dung cuon som | `com.miui.screenshot.accessibility.r#M()` (tong chieu cao > `q.r`) |
| Tang toc do cuon tu dong | inner `r$c#run()` (field int = thoi gian swipe, mac dinh 350ms qua `u1.A#j(Context)`) |
| Giam delay giua 2 lan chup | `r$e#run()` goi `Thread.sleep(650)` |
| Tang buoc cuon | `q#k()` - Rect swipe `q.l` |
| Luot xem anh dai nhanh hon | `ScreenshotScrollView.onTouchEvent()` goi `Scroller#fling(-n(7000))` |
| An toan khi bitmap dai qua lon | `ScreenshotScrollView#i()` - tu thu nho neu `Bitmap.createBitmap()` that bai |

## Cach dung

1. Cai APK build tu CI (tab **Actions** -> artifact `LongShotLimit-release`).
2. Bat module trong **LSPosed**, chon scope **Man hinh chup / Screenshot (`com.miui.screenshot`)**.
3. Mo lai app Screenshot (hoac restart device).
4. Mo app **Long Shot Limit** de tuy chinh:
   - **Gioi han chieu cao**: theo so man hinh (mac dinh HyperOS = 8), theo pixel, hoac khong gioi han.
   - **Thoi gian 1 lan cuon**: mac dinh 350ms -> de 100-150ms de cuon nhanh.
   - **Do tre chup sau khi cuon**: mac dinh 650ms -> de 150-250ms de nhanh.
   - **He so buoc cuon**: 1.0 = goc, tang len de moi lan cuon lay duoc nhieu noi dung hon (co the lam thieu noi dung neu qua lon).
   - **He so luot xem anh dai**: tang toc do fling khi xem anh dai da chup xong.
   - **Tu thu nho khi bitmap qua lon**: Android/Skia gioi han 1 bitmap ~32767px; neu qua gioi han nay module se tu thu nho de van luu duoc anh.

## Build

- Push len repo -> GitHub Actions tu build APK (`.github/workflows/build.yml`).
- Build local:

```bash
./gradlew assembleRelease
# output: app/build/outputs/apk/release/app-release.apk
```

## Ghi chu

- Module doc cau hinh trong app bi hook qua `content://com.tahndz.miuilongshot.config` (ConfigProvider) + fallback file `/data/local/tmp/miuilongshot.json`.
- Khong sua file nao cua he thong, chi hook runtime bang LSPosed.
- Neu HyperOS cap moi doi ten class (obfuscated), module van tu tim field theo dau hieu (static int lon nhat = screenHeight*8, field int duy nhat cua ScrollTask...).
