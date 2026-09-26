/*
 * jPSXdec: PlayStation 1 Media Decoder/Converter in Java
 * Copyright (C) 2007-2023  Michael Sabin
 * All rights reserved.
 *
 * Redistribution and use of the jPSXdec code or any derivative works are
 * permitted provided that the following conditions are met:
 *
 *  * Redistributions may not be sold, nor may they be used in commercial
 *    or revenue-generating business activities.
 *
 *  * Redistributions that are modified from the original source must
 *    include the complete source code, including the source code for all
 *    components used by a binary built from the modified sources. However, as
 *    a special exception, the source code distributed need not include
 *    anything that is normally distributed (in either source or binary form)
 *    with the major components (compiler, kernel, and so on) of the operating
 *    system on which the executable runs, unless that component itself
 *    accompanies the executable.
 *
 *  * Redistributions must reproduce the above copyright notice, this list
 *    of conditions and the following disclaimer in the documentation and/or
 *    other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS
 * IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A
 * PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER
 * OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package jpsxdec.util.ffmpeg;

import java.io.File;
import java.io.IOException;
import java.util.List;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class FfmpegEncoderTest {

    @Rule
    public TemporaryFolder _tempFolder = new TemporaryFolder();

    private static int argIndex(List<String> cmd, String sArg) {
        int i = cmd.indexOf(sArg);
        Assert.assertTrue("Missing " + sArg + " in " + cmd, i >= 0);
        return i;
    }

    @Test
    public void commandWithAudio() {
        FfmpegEncoder enc = new FfmpegEncoder(new File("ffmpeg"), 18, "medium");
        List<String> cmd = enc.buildCommand(new File("in.avi"), new File("out.mp4"), true);

        Assert.assertEquals("ffmpeg", cmd.get(0));
        Assert.assertEquals("in.avi", cmd.get(argIndex(cmd, "-i") + 1));
        Assert.assertEquals("out.mp4", cmd.get(cmd.size() - 1));
        Assert.assertEquals("libx264", cmd.get(argIndex(cmd, "-c:v") + 1));
        Assert.assertEquals("18", cmd.get(argIndex(cmd, "-crf") + 1));
        Assert.assertEquals("medium", cmd.get(argIndex(cmd, "-preset") + 1));
        Assert.assertEquals("yuv420p", cmd.get(argIndex(cmd, "-pix_fmt") + 1));
        Assert.assertEquals("aac", cmd.get(argIndex(cmd, "-c:a") + 1));
        Assert.assertEquals("48000", cmd.get(argIndex(cmd, "-ar") + 1));
        Assert.assertTrue(cmd.contains("0:a:0"));

        String sFilter = cmd.get(argIndex(cmd, "-vf") + 1);
        Assert.assertTrue(sFilter.contains("range=tv"));
        Assert.assertTrue(sFilter.contains("colorspace=smpte170m"));
        Assert.assertTrue(sFilter.contains("chroma_location=center"));
    }

    @Test
    public void commandWithoutAudio() {
        FfmpegEncoder enc = new FfmpegEncoder(new File("ffmpeg"),
                FfmpegEncoder.DEFAULT_CRF, FfmpegEncoder.DEFAULT_PRESET);
        List<String> cmd = enc.buildCommand(new File("in.avi"), new File("out.mp4"), false);
        Assert.assertFalse(cmd.contains("0:a:0"));
        Assert.assertFalse(cmd.contains("-c:a"));
    }

    @Test
    public void pixelAspectRatio() {
        FfmpegEncoder square = new FfmpegEncoder(new File("ffmpeg"),
                FfmpegEncoder.DEFAULT_CRF, FfmpegEncoder.DEFAULT_PRESET);
        List<String> cmd = square.buildCommand(new File("in.avi"), new File("out.mp4"), false);
        Assert.assertFalse(cmd.get(argIndex(cmd, "-vf") + 1).contains("setsar"));

        FfmpegEncoder wide = new FfmpegEncoder(new File("ffmpeg"),
                FfmpegEncoder.DEFAULT_CRF, FfmpegEncoder.DEFAULT_PRESET, 8, 7);
        cmd = wide.buildCommand(new File("in.avi"), new File("out.mp4"), false);
        Assert.assertTrue(cmd.get(argIndex(cmd, "-vf") + 1).endsWith(",setsar=8/7"));
    }

    @Test
    public void parsePar() {
        Assert.assertArrayEquals(new int[] {8, 7}, FfmpegEncoder.parsePar("8:7"));
        Assert.assertArrayEquals(new int[] {32, 35}, FfmpegEncoder.parsePar(" 32 / 35 "));
        Assert.assertNull(FfmpegEncoder.parsePar("8"));
        Assert.assertNull(FfmpegEncoder.parsePar("8:0"));
        Assert.assertNull(FfmpegEncoder.parsePar("-8:7"));
        Assert.assertNull(FfmpegEncoder.parsePar("a:b"));
        Assert.assertNull(FfmpegEncoder.parsePar("1:2:3"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void invalidCrf() {
        new FfmpegEncoder(new File("ffmpeg"), FfmpegEncoder.MAX_CRF + 1, FfmpegEncoder.DEFAULT_PRESET);
    }

    @Test(expected = IllegalArgumentException.class)
    public void invalidPreset() {
        new FfmpegEncoder(new File("ffmpeg"), FfmpegEncoder.DEFAULT_CRF, "turbo");
    }

    @Test
    public void findOnPath() throws IOException {
        File empty = _tempFolder.newFolder("empty");
        File winDir = _tempFolder.newFolder("win");
        File winExe = new File(winDir, "ffmpeg.exe");
        Assert.assertTrue(winExe.createNewFile());
        File unixDir = _tempFolder.newFolder("unix");
        File unixExe = new File(unixDir, "ffmpeg");
        Assert.assertTrue(unixExe.createNewFile());

        String sPath = empty.getPath() + File.pathSeparator + File.pathSeparator
                     + "\"" + winDir.getPath() + "\"" + File.pathSeparator + unixDir.getPath();

        Assert.assertEquals(winExe, FfmpegEncoder.findOnPath(sPath, true));
        Assert.assertEquals(unixExe, FfmpegEncoder.findOnPath(sPath, false));
        Assert.assertNull(FfmpegEncoder.findOnPath(empty.getPath(), true));
        Assert.assertNull(FfmpegEncoder.findOnPath(null, true));
    }

    @Test
    public void locateUserPath() throws IOException {
        File dir = _tempFolder.newFolder("user");
        boolean blnWindows = File.separatorChar == '\\';
        File exe = new File(dir, blnWindows ? "ffmpeg.exe" : "ffmpeg");
        Assert.assertTrue(exe.createNewFile());

        Assert.assertEquals(exe, FfmpegEncoder.locate(dir.getPath()));
        Assert.assertEquals(exe, FfmpegEncoder.locate(exe.getPath()));
        Assert.assertNull(FfmpegEncoder.locate(new File(dir, "missing").getPath()));
    }
}
