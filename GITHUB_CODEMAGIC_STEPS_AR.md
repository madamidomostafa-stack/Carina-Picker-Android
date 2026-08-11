# تشغيل Carina Picker وبناء APK على Codemagic

هذه النسخة مجهزة لبناء **Debug APK قابل للتثبيت مباشرة على جهاز Android واحد**، ولا تحتاج Keystore في هذه المرحلة.

## 1) رفع المشروع إلى GitHub

1. افتح GitHub وسجل الدخول.
2. أنشئ Repository جديد، وليكن اسمه `CarinaPickerAndroid`.
3. ارفع **محتويات هذا المجلد نفسها** إلى جذر الـRepository، وليس المجلد كملف ZIP.
4. تأكد أن ملف `codemagic.yaml` ظاهر في جذر الـRepository بجانب `build.gradle` و`settings.gradle`.

## 2) ربط GitHub بـ Codemagic

1. افتح Codemagic وسجل الدخول.
2. اختر **Add application**.
3. اربط حساب GitHub ثم اختر Repository الخاص بالمشروع.
4. بعد إضافة التطبيق، اطلب من Codemagic فحص `codemagic.yaml`.
5. اختر Workflow باسم:
   `Carina Picker - Debug APK`
6. اضغط **Start new build**.

## 3) بعد انتهاء Build

في صفحة الـBuild ستجد Artifact باسم تقريبًا:

`app-debug.apk`

قم بتنزيله، ثم انقله إلى جهاز الـHandheld وثبته.

## 4) تثبيت APK على الجهاز

قد يطلب Android السماح بالتثبيت من هذا المصدر. اسم الإعداد يختلف حسب الشركة المصنعة للجهاز. بعد السماح، افتح `app-debug.apk` وثبته.

## ملاحظات

- هذه نسخة **Debug** للتجربة على جهاز واحد.
- التطبيق Offline والداتا محفوظة محليًا على الجهاز.
- Workflow يقوم بتنزيل Gradle 9.4.1 أثناء الـBuild ثم ينفذ `assembleDebug`.
- `codemagic.yaml` يجب أن يبقى في جذر الـRepository.
- عند الانتقال لاحقًا إلى نسخة Release أو Google Play سنحتاج Android Keystore للتوقيع.
