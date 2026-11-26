package org.teamtalkextro.backend;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ExperimentalAudioCaptureTest {

    @Test
    public void testRunCaptureLoopNullSessionDoesNotThrow() {
        ExperimentalAudioCapture capture = new ExperimentalAudioCapture(null);
        // Must return cleanly without NullPointerException even if captureSession was cleared concurrently
        capture.runCaptureLoop(null);
        assertFalse(capture.isRunning());
    }

    @Test
    public void testRunCaptureLoopInvalidOrReleasedSessionDoesNotThrow() {
        ExperimentalAudioCapture capture = new ExperimentalAudioCapture(null);
        ExperimentalAudioCapture.CaptureSession zeroFrame = new ExperimentalAudioCapture.CaptureSession(
                null, null, 48000, 1, 0, 1, 1.0f, 1, null);
        capture.runCaptureLoop(zeroFrame);

        ExperimentalAudioCapture.CaptureSession released = new ExperimentalAudioCapture.CaptureSession(
                null, null, 48000, 2, 960, 1, 1.0f, 1, null);
        released.stopRecordingOnly();
        assertTrue(released.isReleased());
        capture.runCaptureLoop(released);

        // Double release must be safe and idempotent
        released.releaseResources();
        released.releaseResources();
        released.stopAndRelease();
    }

    @Test
    public void testStartWithNullConfigReturnsFalseSafely() {
        ExperimentalAudioCapture capture = new ExperimentalAudioCapture(null);
        assertFalse(capture.start(null, null));
        assertFalse(capture.isRunning());
    }
}
