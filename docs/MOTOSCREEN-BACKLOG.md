# MotoScreen TH — งานที่ตัดสินใจแล้ว รอแก้รวมรอบเดียว

## B1 · สลับภาษาแล้วค้าง (พบใน v9, 3 พ.ย. 2026) — ตัดสินใจ: A + B · ✅ แก้ใน v10
**อาการ:** กดปุ่ม TH | EN แล้วหน้าจอค้างไปช่วงหนึ่ง
**สาเหตุ (จากโค้ด):**
- หน่วงเอง 240 ms ก่อนเรียก `setApplicationLocales`
- Android สร้าง MainActivity ใหม่ทั้งหน้า และ onCreate/onResume รันงานเหมือนเปิดแอปครั้งแรก
  (ถอดรูปรถบน main thread, maybeAutoConnect, maybeCheckUpdate, ถามสิทธิ์/WhatsNew)
- ไม่มี transition ระหว่างหน้าเก่ากับหน้าใหม่

**แก้ A — ให้สร้างหน้าใหม่เบาลง**
- ตั้ง flag "recreated for language" (static/companion) ก่อน setLang
- ถ้า flag นี้ตั้งอยู่: ข้าม maybeAutoConnect, maybeCheckUpdate, maybeAskNearbyUpfront,
  Xiaomi autostart, WhatsNew/Telemetry และ wizard check
- ย้ายการถอดรูป hero (BitmapFactory) ไปทำบน background thread แล้วค่อย set บน UI
- ลดหน่วง 240 ms → ~120 ms

**แก้ B — แอนิเมชันจางออก/จางเข้า**
- ตอนกด: thumb เลื่อน + content (R.id.main) alpha 1→0 ภายใน ~150 ms แล้วเรียก setLang
- หลังสร้างใหม่ (มี flag): content เริ่มที่ alpha 0 แล้ว animate → 1 ภายใน ~200 ms
- ทำใน SetupWizardActivity ด้วย (มีปุ่มภาษาในหน้าแรกของ wizard)

**ต้องเช็คตอนแก้:** สลับภาษาตอนกำลังฉายภาพอยู่ ภาพบนจอรถต้องไม่สะดุด
(projection อยู่ใน service แต่ต้องดูว่า MainActivity.onDestroy ไม่ไปหยุดอะไร)

**ยังรอจากผู้ใช้:** ค้างนานกี่วินาที / ตอนนั้นต่อจอรถอยู่ไหม / log รอบที่กด

**แก้แล้วใน v10:** flag `MotoScreen.langSwitching` (หมดอายุเอง 5 วิ), ข้าม auto-connect / update / prompts / wizard,
รูป hero ถอดเบื้องหลัง + cache ข้ามการสร้างหน้าใหม่, จางออก 140 ms → จางเข้า 200 ms (หน้าแรก + wizard),
onDestroy ไม่ teardown การเชื่อมต่อถ้าเป็นการสร้างหน้าใหม่ (isChangingConfigurations)

## B2 · ชื่อแอปมีคำว่า "TH" ดูแปลก (3 พ.ย. 2026) — เสนอ: ตัด TH → "MotoScreen"
- เปลี่ยน app_name / brand_wordmark ทุก locale เป็น "MotoScreen"
- wordmark หน้าแรก: "Moto" ขาว + "Screen" สีหลัก (โค้ดเดิมแบ่งที่ช่องว่าง → ต้องแบ่งที่ตัว S แทน)
- ข้อความในแอปที่มีคำว่า "MotoScreen TH" (wizard, คู่มือ, share_text/footer, release notes, workflow ชื่อไฟล์ APK) → "MotoScreen"
- ไม่เปลี่ยน package `th.motoscreen.app` (ผู้ใช้มองไม่เห็น และถ้าเปลี่ยนจะลงทับเวอร์ชันเดิมไม่ได้)
- รอผู้ใช้ยืนยันชื่อ
