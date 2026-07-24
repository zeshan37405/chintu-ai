package com.zeshan.chintuai;

import android.Manifest;
import android.accessibilityservice.AccessibilityService;
import android.app.NotificationManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.provider.AlarmClock;
import android.provider.ContactsContract;
import android.provider.MediaStore;
import android.provider.Settings;
import android.view.KeyEvent;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Executes commands from the foreground hands-free service without requiring the activity UI. */
public final class BackgroundCommandExecutor {
    private BackgroundCommandExecutor() {
    }

    public static final class Result {
        public final boolean handled;
        public final String message;
        public final boolean stopHandsFree;

        Result(boolean handled, String message, boolean stopHandsFree) {
            this.handled = handled;
            this.message = message == null ? "" : message;
            this.stopHandsFree = stopHandsFree;
        }

        static Result ok(String message) {
            return new Result(true, message, false);
        }

        static Result stop(String message) {
            return new Result(true, message, true);
        }

        static Result fail(String message) {
            return new Result(false, message, false);
        }
    }

    public static Result execute(Context context, String raw) {
        CommandEngine.ParsedCommand command = CommandEngine.parse(raw);
        switch (command.type) {
            case HANDS_FREE_OFF:
                return Result.stop("ہینڈز فری موڈ بند کر دیا ہے");
            case HANDS_FREE_ON:
                return Result.ok("ہینڈز فری موڈ پہلے سے چل رہا ہے");
            case BLOCKED_FINANCIAL:
                return Result.fail("مالی لین دین، پاس ورڈ اور حساس اکاؤنٹ تبدیلیاں بند ہیں");
            case HELP:
                return openMainActivity(context, raw, "تمام کمانڈز کھول رہا ہوں");
            case TIME:
                return Result.ok("اس وقت " + new SimpleDateFormat(
                        "hh:mm a", Locale.getDefault()).format(new Date()) + " ہوئے ہیں");
            case DATE:
                return Result.ok("آج " + new SimpleDateFormat(
                        "EEEE، d MMMM yyyy", new Locale("ur", "PK")).format(new Date()) + " ہے");
            case CALL:
                return call(context, command.argument);
            case SMS:
                return message(context, command.argument);
            case CONTACT_LOOKUP:
                return lookupNumber(context, command.argument);
            case TORCH_ON:
                return torch(context, true);
            case TORCH_OFF:
                return torch(context, false);
            case ALARM:
                return alarm(context, raw);
            case TIMER:
                return timer(context, raw);
            case VOLUME_UP:
                return adjustVolume(context, AudioManager.ADJUST_RAISE, "آواز بڑھا دی ہے");
            case VOLUME_DOWN:
                return adjustVolume(context, AudioManager.ADJUST_LOWER, "آواز کم کر دی ہے");
            case VOLUME_MUTE:
                return adjustVolume(context, AudioManager.ADJUST_MUTE, "آواز بند کر دی ہے");
            case VOLUME_MAX:
                return maxVolume(context);
            case MEDIA_PLAY_PAUSE:
                return mediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, "پلے یا پاز کر دیا ہے");
            case MEDIA_NEXT:
                return mediaKey(context, KeyEvent.KEYCODE_MEDIA_NEXT, "اگلا ٹریک چلا دیا ہے");
            case MEDIA_PREVIOUS:
                return mediaKey(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS, "پچھلا ٹریک چلا دیا ہے");
            case MEDIA_STOP:
                return mediaKey(context, KeyEvent.KEYCODE_MEDIA_STOP, "میڈیا روک دیا ہے");
            case BRIGHTNESS_UP:
                return brightness(context, 45, false);
            case BRIGHTNESS_DOWN:
                return brightness(context, -45, false);
            case BRIGHTNESS_MAX:
                return brightness(context, 0, true);
            case GLOBAL_HOME:
                return globalAction(AccessibilityService.GLOBAL_ACTION_HOME, "ہوم اسکرین کھول دی ہے");
            case GLOBAL_BACK:
                return globalAction(AccessibilityService.GLOBAL_ACTION_BACK, "واپس چلا گیا ہوں");
            case GLOBAL_RECENTS:
                return globalAction(AccessibilityService.GLOBAL_ACTION_RECENTS, "حالیہ ایپس کھول دی ہیں");
            case GLOBAL_NOTIFICATIONS:
                return globalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS,
                        "نوٹیفکیشن کھول دیے ہیں");
            case GLOBAL_QUICK_SETTINGS:
                return globalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS,
                        "کوئیک سیٹنگز کھول دی ہیں");
            case GLOBAL_LOCK:
                if (Build.VERSION.SDK_INT >= 28) {
                    return globalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN,
                            "فون لاک کر دیا ہے");
                }
                return Result.fail("اس Android ورژن پر وائس لاک دستیاب نہیں");
            case GLOBAL_SCREENSHOT:
                if (Build.VERSION.SDK_INT >= 28) {
                    return globalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT,
                            "اسکرین شاٹ لے لیا ہے");
                }
                return Result.fail("اس Android ورژن پر اسکرین شاٹ دستیاب نہیں");
            case COPY:
                return copy(context, command.argument);
            case SHARE:
                return share(context, command.argument);
            case YOUTUBE_SEARCH:
                if (command.argument.isEmpty()) return openApp(context, "یوٹیوب");
                return openUrl(context,
                        "https://www.youtube.com/results?search_query=" + Uri.encode(command.argument),
                        "یوٹیوب پر تلاش کر رہا ہوں");
            case WEATHER:
                String weather = command.argument.isEmpty()
                        ? "حسن ابدال موسم آج" : command.argument + " موسم";
                return google(context, weather, "موسم تلاش کر رہا ہوں");
            case GOOGLE_SEARCH:
                return google(context, command.argument.isEmpty() ? raw : command.argument,
                        "گوگل پر تلاش کر رہا ہوں");
            case MAP_SEARCH:
                if (command.argument.isEmpty()) return openApp(context, "گوگل میپس");
                return map(context, command.argument);
            case OPEN_CONTACTS:
                return start(context,
                        new Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI),
                        "کانٹیکٹس کھول رہا ہوں");
            case OPEN_DIALER:
                return start(context, new Intent(Intent.ACTION_DIAL, Uri.parse("tel:")),
                        "ڈائلر کھول رہا ہوں");
            case OPEN_MESSAGES:
                return start(context, Intent.makeMainSelectorActivity(
                                Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING),
                        "میسجز کھول رہا ہوں");
            case OPEN_GMAIL:
                return openApp(context, "جی میل");
            case OPEN_CALCULATOR:
                return openApp(context, "کیلکولیٹر");
            case OPEN_CAMERA:
                return start(context, new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
                        "کیمرہ کھول رہا ہوں");
            case OPEN_GALLERY:
                Result gallery = openApp(context, "گیلری");
                if (gallery.handled) return gallery;
                return start(context, new Intent(Intent.ACTION_VIEW,
                                MediaStore.Images.Media.EXTERNAL_CONTENT_URI),
                        "گیلری کھول رہا ہوں");
            case OPEN_WIFI:
                return internetPanel(context);
            case OPEN_BLUETOOTH:
                return start(context, new Intent(Settings.ACTION_BLUETOOTH_SETTINGS),
                        "بلوٹوتھ سیٹنگز کھول رہا ہوں");
            case OPEN_MOBILE_DATA:
                return start(context, new Intent(Settings.ACTION_DATA_ROAMING_SETTINGS),
                        "موبائل ڈیٹا سیٹنگز کھول رہا ہوں");
            case OPEN_SETTINGS:
                return start(context, new Intent(Settings.ACTION_SETTINGS),
                        "سیٹنگز کھول رہا ہوں");
            case OPEN_PLAY_STORE:
                return openApp(context, "پلے اسٹور");
            case OPEN_CALENDAR:
                return start(context, new Intent(Intent.ACTION_VIEW,
                                Uri.parse("content://com.android.calendar/time/")),
                        "کیلنڈر کھول رہا ہوں");
            case OPEN_CLOCK:
                return start(context, new Intent(AlarmClock.ACTION_SHOW_ALARMS),
                        "گھڑی کھول رہا ہوں");
            case OPEN_FILES:
                return openFiles(context);
            case OPEN_APP:
                return openApp(context, command.argument);
            case UNKNOWN:
            default:
                return google(context, raw, "یہ کمانڈ گوگل پر تلاش کر رہا ہوں");
        }
    }

    private static Result call(Context context, String argument) {
        String number = resolveNumber(context, argument);
        if (number.isEmpty()) return Result.fail("یہ کانٹیکٹ فون ڈائریکٹری میں نہیں ملا");
        if (context.checkSelfPermission(Manifest.permission.CALL_PHONE)
                != PackageManager.PERMISSION_GRANTED) {
            return openMainActivity(context, "", "براہ راست کال کے لیے فون کی اجازت دیں");
        }
        Intent intent = new Intent(Intent.ACTION_CALL, Uri.fromParts("tel", number, null));
        return start(context, intent, "کال ملا رہا ہوں");
    }

    private static Result message(Context context, String argument) {
        String number = resolveNumber(context, argument);
        if (number.isEmpty()) return Result.fail("یہ کانٹیکٹ فون ڈائریکٹری میں نہیں ملا");
        return start(context, new Intent(Intent.ACTION_SENDTO,
                        Uri.fromParts("smsto", number, null)),
                "میسج لکھنے کا صفحہ کھول رہا ہوں");
    }

    private static Result lookupNumber(Context context, String requested) {
        ContactCandidate candidate = findBestContact(context, requested);
        if (candidate == null) return Result.fail("یہ نام فون ڈائریکٹری میں نہیں ملا");
        ClipboardManager clipboard =
                (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText(candidate.name, candidate.number));
        }
        return Result.ok(candidate.name + " کا نمبر " + candidate.number + " ہے اور کاپی ہو گیا ہے");
    }

    private static String resolveNumber(Context context, String argument) {
        if (argument == null) return "";
        String number = CommandEngine.extractPhoneNumber(argument);
        if (!number.isEmpty()) return number;
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS)
                != PackageManager.PERMISSION_GRANTED) return "";
        ContactCandidate candidate = findBestContact(context, argument);
        return candidate == null ? "" : candidate.number;
    }

    private static ContactCandidate findBestContact(Context context, String requested) {
        if (requested == null || requested.trim().isEmpty()) return null;
        String[] projection = {
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.TYPE,
                ContactsContract.CommonDataKinds.Phone.LABEL
        };
        List<ContactCandidate> matches = new ArrayList<>();
        try (Cursor cursor = context.getContentResolver().query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection, null, null, null)) {
            if (cursor == null) return null;
            int nameIndex = cursor.getColumnIndex(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME);
            int numberIndex = cursor.getColumnIndex(
                    ContactsContract.CommonDataKinds.Phone.NUMBER);
            int typeIndex = cursor.getColumnIndex(
                    ContactsContract.CommonDataKinds.Phone.TYPE);
            int labelIndex = cursor.getColumnIndex(
                    ContactsContract.CommonDataKinds.Phone.LABEL);
            int minimum = ContactMatcher.minimumAcceptedScore(requested);
            while (cursor.moveToNext()) {
                String name = getString(cursor, nameIndex);
                String number = getString(cursor, numberIndex);
                if (name.isEmpty() || number.isEmpty()) continue;
                int type = typeIndex >= 0 && !cursor.isNull(typeIndex) ? cursor.getInt(typeIndex) : 0;
                String custom = getString(cursor, labelIndex);
                String label = ContactsContract.CommonDataKinds.Phone.getTypeLabel(
                        context.getResources(), type, custom).toString();
                int score = ContactMatcher.score(requested, name, label);
                if (score < minimum) continue;
                if (ContactMatcher.isExactName(requested, name)) score += 30;
                if (type == ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE) score += 3;
                matches.add(new ContactCandidate(name, number, score));
            }
        } catch (SecurityException | RuntimeException ignored) {
            return null;
        }
        if (matches.isEmpty()) return null;
        Collections.sort(matches, (a, b) -> Integer.compare(b.score, a.score));
        return matches.get(0);
    }

    private static Result torch(Context context, boolean enabled) {
        if (context.checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            return Result.fail("ٹارچ کے لیے کیمرہ کی اجازت دیں");
        }
        CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        if (manager == null) return Result.fail("ٹارچ دستیاب نہیں");
        try {
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
                Boolean flash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (Boolean.TRUE.equals(flash)
                        && (facing == null || facing == CameraCharacteristics.LENS_FACING_BACK)) {
                    manager.setTorchMode(id, enabled);
                    return Result.ok(enabled ? "ٹارچ آن کر دی ہے" : "ٹارچ بند کر دی ہے");
                }
            }
        } catch (CameraAccessException | SecurityException ignored) {
            return Result.fail("ٹارچ چلانے میں مسئلہ آیا");
        }
        return Result.fail("اس فون میں ٹارچ دستیاب نہیں");
    }

    private static Result alarm(Context context, String raw) {
        CommandEngine.ParsedTime time = CommandEngine.parseClockTime(raw);
        Intent intent = new Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_MESSAGE, "Chintu Alarm")
                .putExtra(AlarmClock.EXTRA_SKIP_UI, false);
        if (time != null) {
            intent.putExtra(AlarmClock.EXTRA_HOUR, time.hour);
            intent.putExtra(AlarmClock.EXTRA_MINUTES, time.minute);
        }
        return start(context, intent, time == null
                ? "وقت واضح نہیں ملا، الارم ایپ کھول رہا ہوں" : "الارم تیار کر رہا ہوں");
    }

    private static Result timer(Context context, String raw) {
        int seconds = CommandEngine.parseDurationSeconds(raw);
        Intent intent = new Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_MESSAGE, "Chintu Timer")
                .putExtra(AlarmClock.EXTRA_SKIP_UI, false);
        if (seconds > 0) intent.putExtra(AlarmClock.EXTRA_LENGTH, seconds);
        return start(context, intent, seconds > 0
                ? "ٹائمر تیار کر رہا ہوں" : "مدت واضح نہیں ملی، ٹائمر کھول رہا ہوں");
    }

    private static Result adjustVolume(Context context, int direction, String message) {
        AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audio == null) return Result.fail("آواز کنٹرول دستیاب نہیں");
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI);
        return Result.ok(message);
    }

    private static Result maxVolume(Context context) {
        AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audio == null) return Result.fail("آواز کنٹرول دستیاب نہیں");
        audio.setStreamVolume(AudioManager.STREAM_MUSIC,
                audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC), AudioManager.FLAG_SHOW_UI);
        return Result.ok("آواز زیادہ سے زیادہ کر دی ہے");
    }

    private static Result mediaKey(Context context, int keyCode, String message) {
        AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audio == null) return Result.fail("میڈیا کنٹرول دستیاب نہیں");
        long now = android.os.SystemClock.uptimeMillis();
        audio.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0));
        audio.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0));
        return Result.ok(message);
    }

    private static Result brightness(Context context, int delta, boolean maximum) {
        if (!Settings.System.canWrite(context)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.fromParts("package", context.getPackageName(), null));
            return start(context, intent, "روشنی کنٹرول کے لیے اجازت دیں");
        }
        try {
            int current = Settings.System.getInt(context.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS, 128);
            int value = maximum ? 255 : Math.max(10, Math.min(255, current + delta));
            Settings.System.putInt(context.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
            Settings.System.putInt(context.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS, value);
            return Result.ok(maximum ? "روشنی فل کر دی ہے"
                    : delta > 0 ? "روشنی بڑھا دی ہے" : "روشنی کم کر دی ہے");
        } catch (RuntimeException error) {
            return Result.fail("روشنی تبدیل نہیں ہوئی");
        }
    }

    private static Result globalAction(int action, String message) {
        if (!ChintuAccessibilityService.isConnected()) {
            return Result.fail("فون کنٹرول کے لیے چنٹو Accessibility سروس آن کریں");
        }
        return ChintuAccessibilityService.perform(action)
                ? Result.ok(message) : Result.fail("یہ فون کنٹرول مکمل نہیں ہوا");
    }

    private static Result copy(Context context, String text) {
        if (text == null || text.trim().isEmpty()) return Result.fail("کاپی کرنے کا متن واضح نہیں");
        ClipboardManager clipboard =
                (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) return Result.fail("کلپ بورڈ دستیاب نہیں");
        clipboard.setPrimaryClip(ClipData.newPlainText("Chintu", text));
        return Result.ok("متن کاپی ہو گیا");
    }

    private static Result share(Context context, String text) {
        if (text == null || text.trim().isEmpty()) return Result.fail("شیئر کرنے کا متن واضح نہیں");
        Intent share = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, text);
        return start(context, Intent.createChooser(share, "ایپ منتخب کریں"),
                "شیئر کرنے کے لیے ایپ منتخب کریں");
    }

    private static Result google(Context context, String query, String message) {
        if (query == null || query.trim().isEmpty()) return Result.fail("تلاش کے الفاظ واضح نہیں");
        return openUrl(context, "https://www.google.com/search?q=" + Uri.encode(query), message);
    }

    private static Result map(Context context, String query) {
        Intent geo = new Intent(Intent.ACTION_VIEW,
                Uri.parse("geo:0,0?q=" + Uri.encode(query)));
        Result result = start(context, geo, "میپس میں تلاش کر رہا ہوں");
        if (result.handled) return result;
        return openUrl(context,
                "https://www.google.com/maps/search/?api=1&query=" + Uri.encode(query),
                "میپس میں تلاش کر رہا ہوں");
    }

    private static Result internetPanel(Context context) {
        if (Build.VERSION.SDK_INT >= 29) {
            Result panel = start(context, new Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY),
                    "انٹرنیٹ کنٹرول کھول رہا ہوں");
            if (panel.handled) return panel;
        }
        return start(context, new Intent(Settings.ACTION_WIFI_SETTINGS),
                "وائی فائی سیٹنگز کھول رہا ہوں");
    }

    private static Result openFiles(Context context) {
        Result known = openApp(context, "فائلز");
        if (known.handled) return known;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*");
        return start(context, intent, "فائلز کھول رہا ہوں");
    }

    private static Result openApp(Context context, String requested) {
        if (requested == null || requested.trim().isEmpty()) return Result.fail("ایپ کا نام واضح نہیں");
        PackageManager manager = context.getPackageManager();
        AppCatalog.AppMatch known = AppCatalog.findBest(requested);
        if (known != null && known.score >= 78) {
            for (String packageName : known.app.packageNames) {
                Intent launch = manager.getLaunchIntentForPackage(packageName);
                if (launch != null) return start(context, launch, known.app.displayName + " کھول رہا ہوں");
            }
        }
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        ResolveInfo best = null;
        int bestScore = 0;
        for (ResolveInfo info : manager.queryIntentActivities(query, 0)) {
            if (info.activityInfo == null) continue;
            CharSequence label = info.loadLabel(manager);
            if (label == null) continue;
            int score = AppCatalog.scoreName(requested, label.toString());
            if (score > bestScore) {
                bestScore = score;
                best = info;
            }
        }
        if (best != null && bestScore >= 68) {
            Intent intent = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(new ComponentName(best.activityInfo.packageName, best.activityInfo.name));
            CharSequence label = best.loadLabel(manager);
            return start(context, intent,
                    (label == null ? requested : label.toString()) + " کھول رہا ہوں");
        }
        if (known != null && known.app.fallbackUrl != null) {
            return openUrl(context, known.app.fallbackUrl,
                    known.app.displayName + " ویب پر کھول رہا ہوں");
        }
        return Result.fail(requested + " فون میں نہیں ملی");
    }

    private static Result openUrl(Context context, String url, String message) {
        return start(context, new Intent(Intent.ACTION_VIEW, Uri.parse(url)), message);
    }

    private static Result openMainActivity(Context context, String command, String message) {
        Intent intent = new Intent(context, ChintuActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (command != null && !command.isEmpty()) {
            intent.putExtra(ChintuActivity.EXTRA_PENDING_COMMAND, command);
        }
        return start(context, intent, message);
    }

    private static Result start(Context context, Intent intent, String message) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return Result.ok(message);
        } catch (RuntimeException error) {
            return Result.fail(message.isEmpty() ? "یہ کام دستیاب نہیں" : message + "، مگر ایپ دستیاب نہیں");
        }
    }

    private static String getString(Cursor cursor, int index) {
        if (index < 0 || cursor.isNull(index)) return "";
        String value = cursor.getString(index);
        return value == null ? "" : value.trim();
    }

    private static final class ContactCandidate {
        final String name;
        final String number;
        final int score;

        ContactCandidate(String name, String number, int score) {
            this.name = name;
            this.number = number;
            this.score = score;
        }
    }
}
