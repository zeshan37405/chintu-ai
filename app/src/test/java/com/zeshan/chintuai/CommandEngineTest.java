package com.zeshan.chintuai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CommandEngineTest {
    @Test
    public void routesOpenMessagesBeforeSmsContactAction() {
        assertEquals(CommandEngine.Type.OPEN_MESSAGES,
                CommandEngine.parse("میسجز کھولو").type);
        assertEquals(CommandEngine.Type.OPEN_MESSAGES,
                CommandEngine.parse("SMS کھولو").type);
    }

    @Test
    public void extractsHomeContactFromCallCommand() {
        CommandEngine.ParsedCommand command = CommandEngine.parse("ہوم کو کال کرو");
        assertEquals(CommandEngine.Type.CALL, command.type);
        assertEquals("ہوم", command.argument);
    }

    @Test
    public void parsesWeatherLocationWithoutCommandWords() {
        CommandEngine.ParsedCommand command =
                CommandEngine.parse("حسن ابدال کا موسم بتاؤ");
        assertEquals(CommandEngine.Type.WEATHER, command.type);
        assertEquals("حسن ابدال کا", command.argument);
    }

    @Test
    public void blocksFinancialAndPasswordCommands() {
        assertEquals(CommandEngine.Type.BLOCKED_FINANCIAL,
                CommandEngine.parse("جاز کیش سے رقم ٹرانسفر کرو").type);
        assertEquals(CommandEngine.Type.BLOCKED_FINANCIAL,
                CommandEngine.parse("password change کرو").type);
    }

    @Test
    public void parsesCombinedTimerDuration() {
        assertEquals(4800,
                CommandEngine.parseDurationSeconds("ایک گھنٹہ بیس منٹ کا ٹائمر لگاؤ"));
        assertEquals(300,
                CommandEngine.parseDurationSeconds("پانچ منٹ کا ٹائمر لگاؤ"));
    }

    @Test
    public void parsesUrduClockTime() {
        CommandEngine.ParsedTime time = CommandEngine.parseClockTime("شام ساڑھے پانچ بجے الارم");
        assertEquals(17, time.hour);
        assertEquals(30, time.minute);
    }

    @Test
    public void normalizesArabicAndPersianDigits() {
        assertEquals("0312", CommandEngine.normalizeDigits("۰۳۱۲"));
        assertEquals("0312", CommandEngine.normalizeDigits("٠٣١٢"));
    }

    @Test
    public void recognizesInstalledAppRequest() {
        CommandEngine.ParsedCommand command = CommandEngine.parse("انسٹاگرام کھولو");
        assertEquals(CommandEngine.Type.OPEN_APP, command.type);
        assertTrue(command.argument.contains("انسٹاگرام"));
    }
}
