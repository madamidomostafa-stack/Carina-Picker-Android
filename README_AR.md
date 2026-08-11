# Carina Picker Android - Single Device Offline

هذه نسخة Android أولية لتحويل منطق Excel/VBA Picker إلى تطبيق مستقل يعمل على جهاز Android واحد بدون إنترنت أثناء التشغيل.

## الوظائف الموجودة
- Import Pick File بصيغة CSV أو XLSX (أول Worksheet).
- منع Import أثناء جلسة Picking نشطة.
- منع تكرار Order Number الموجود سابقًا.
- Start / Resume Picking.
- Location Scan ثم SKU Scan قطعة بقطعة.
- Damage: اضغط DAMAGE ثم امسح Barcode للصنف التالف.
- Not Found: يسجل كل الكمية المتبقية Not Found بعد التأكيد.
- Order Progress وElapsed Time.
- Order Complete Summary.
- PICK_DATA / PICK_LOG / EXCEPTIONS_LOG داخل قاعدة بيانات SQLite محلية.
- Reset Session بدون حذف الأوردرات.
- Clear History.
- دعم قارئ Barcode Handheld الذي يعمل كلوحة مفاتيح ويرسل Enter بعد القراءة.

## ملف الاستيراد
العناوين المطلوبة:

Order Number | Branch | SKU | QTY | Location

يمكن أن يكون الملف CSV أو XLSX، ويتم قراءة أول Worksheet في XLSX.

## التشغيل
1. افتح المشروع في Android Studio.
2. انتظر Gradle Sync.
3. وصل جهاز Android مع USB Debugging أو استخدم Emulator.
4. Run > Run 'app'.
5. لعمل APK: Build > Generate App Bundles or APKs > Generate APKs.

## إعدادات المشروع
- Application ID: com.carina.picker
- Minimum Android: API 24
- Compile SDK: 36
- Java: 17
- Android Gradle Plugin: 9.2.0
- قاعدة البيانات: SQLite محلية داخل الجهاز.

## ملاحظات
هذه النسخة مصممة لجهاز واحد. لا يوجد Server أو Sync أو Multi-user. قاعدة البيانات موجودة داخل مساحة التطبيق على نفس الجهاز.

الإصدار: Android Prototype 0.1.0
