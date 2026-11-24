package dev.dsh.miuixbrowser.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 纯 JVM：解码器名 → 软 / 硬件。 */
class PlayerDecoderTest {
    @Test fun googleAndFfmpegDecodersAreSoftware() {
        assertTrue(isSoftwareDecoder("OMX.google.h264.decoder"))
        assertTrue(isSoftwareDecoder("c2.android.avc.decoder"))
        assertTrue(isSoftwareDecoder("c2.google.hevc.decoder"))
        assertTrue(isSoftwareDecoder("ffmpeg-video"))
    }

    @Test fun vendorDecodersAreHardware() {
        assertFalse(isSoftwareDecoder("c2.qti.avc.decoder"))
        assertFalse(isSoftwareDecoder("OMX.qcom.video.decoder.avc"))
        assertFalse(isSoftwareDecoder("c2.mtk.hevc.decoder"))
    }
}
