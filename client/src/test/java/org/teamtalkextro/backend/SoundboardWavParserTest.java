package org.teamtalkextro.backend;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import org.junit.Test;

public class SoundboardWavParserTest {

    private static void writeU16LE(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
    }

    private static void writeU32LE(ByteArrayOutputStream out, long v) {
        out.write((int) (v & 0xFF));
        out.write((int) ((v >> 8) & 0xFF));
        out.write((int) ((v >> 16) & 0xFF));
        out.write((int) ((v >> 24) & 0xFF));
    }

    private static byte[] buildPcm16Wav(int sampleRate, int channels, short[] samples, boolean includeJunkChunk) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write('W'); body.write('A'); body.write('V'); body.write('E');

        if (includeJunkChunk) {
            // Odd-length JUNK chunk (5 bytes + 1 pad byte) to test chunk walking & word alignment
            body.write('J'); body.write('U'); body.write('N'); body.write('K');
            writeU32LE(body, 5);
            body.write(1); body.write(2); body.write(3); body.write(4); body.write(5);
            body.write(0); // pad byte
        }

        // fmt chunk
        body.write('f'); body.write('m'); body.write('t'); body.write(' ');
        writeU32LE(body, 16);
        writeU16LE(body, 1); // PCM
        writeU16LE(body, channels);
        writeU32LE(body, sampleRate);
        writeU32LE(body, (long) sampleRate * channels * 2);
        writeU16LE(body, channels * 2);
        writeU16LE(body, 16);

        // data chunk
        body.write('d'); body.write('a'); body.write('t'); body.write('a');
        writeU32LE(body, samples.length * 2L);
        for (short s : samples) {
            writeU16LE(body, s & 0xFFFF);
        }

        byte[] bodyBytes = body.toByteArray();
        ByteArrayOutputStream riff = new ByteArrayOutputStream();
        riff.write('R'); riff.write('I'); riff.write('F'); riff.write('F');
        writeU32LE(riff, bodyBytes.length);
        riff.write(bodyBytes, 0, bodyBytes.length);
        return riff.toByteArray();
    }

    @Test
    public void testParseStandard16BitMonoWavWithJunkChunk() {
        short[] input = {0, 1000, -1000, 16000, -16000, 32767, -32768};
        byte[] wav = buildPcm16Wav(22050, 1, input, true);

        SoundboardEngine.WavData parsed = SoundboardEngine.parseWavPcm16(wav);
        assertNotNull(parsed);
        assertEquals(22050, parsed.sampleRate);
        assertEquals(1, parsed.channels);
        assertArrayEquals(input, parsed.pcm);
    }

    @Test
    public void testParse16BitStereoWav() {
        short[] input = {100, -100, 2000, -2000, 5000, 6000};
        byte[] wav = buildPcm16Wav(48000, 2, input, false);

        SoundboardEngine.WavData parsed = SoundboardEngine.parseWavPcm16(wav);
        assertNotNull(parsed);
        assertEquals(48000, parsed.sampleRate);
        assertEquals(2, parsed.channels);
        assertArrayEquals(input, parsed.pcm);
    }

    @Test
    public void testRejectInvalidWavData() {
        assertNull(SoundboardEngine.parseWavPcm16(null));
        assertNull(SoundboardEngine.parseWavPcm16(new byte[10]));
        assertNull(SoundboardEngine.parseWavPcm16("NOT_A_WAV_HEADER_0000000000000000000000000000000".getBytes()));
    }

    @Test
    public void testResampleMono24kToStereo48k() {
        // Linear ramp 0, 1000, 2000, 3000 at 24 kHz mono -> 48 kHz stereo (8 frames, 16 shorts)
        short[] mono = {0, 1000, 2000, 3000};
        short[] out = SoundboardEngine.resampleAndConvertChannels(mono, 24000, 1, 48000, 2);

        assertEquals(8 * 2, out.length);
        // Frame 0: L=0, R=0
        assertEquals(0, out[0]);
        assertEquals(0, out[1]);
        // Frame 1 (halfway between 0 and 1000 -> ~500)
        assertEquals(500, out[2]);
        assertEquals(500, out[3]);
        // Frame 2 (exact sample 1 -> 1000)
        assertEquals(1000, out[4]);
        assertEquals(1000, out[5]);
        // Stereo channels identical for mono source
        for (int f = 0; f < 8; f++) {
            assertEquals(out[f * 2], out[f * 2 + 1]);
        }
    }

    @Test
    public void testDownmixStereoToMonoSameRate() {
        short[] stereo = {1000, 3000, -4000, -2000};
        short[] mono = SoundboardEngine.resampleAndConvertChannels(stereo, 48000, 2, 48000, 1);

        assertEquals(2, mono.length);
        assertEquals(2000, mono[0]);
        assertEquals(-3000, mono[1]);
    }

    @Test
    public void testTtsAirClampFactor() {
        assertEquals(0.6f, TtsAirAnnouncer.clampFactor(0.1f), 0.0001f);
        assertEquals(1.6f, TtsAirAnnouncer.clampFactor(3.0f), 0.0001f);
        assertEquals(1.0f, TtsAirAnnouncer.clampFactor(Float.NaN), 0.0001f);
        assertEquals(1.25f, TtsAirAnnouncer.clampFactor(1.25f), 0.0001f);
        assertTrue(SoundboardEngine.resampleAndConvertChannels(null, 48000, 1, 48000, 1).length == 0);
    }
}
