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

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

/** Encodes a lossless intermediate video file (YV12 + PCM AVI) into an
 * H.264 + AAC MP4 by running an external ffmpeg executable.
 *
 * The decoded PSX frames are already 4:2:0 BT.601 YCbCr, so ffmpeg is only
 * told how to tag them (so players show the right colors) and how to
 * compress them. No color conversion or scaling happens here. */
public class FfmpegEncoder {

    private static final Logger LOG = Logger.getLogger(FfmpegEncoder.class.getName());

    /** Lower is better quality. 14 is visually transparent for PSX video
     * and keeps extra bits in dark scenes. */
    public static final int DEFAULT_CRF = 14;
    public static final int MIN_CRF = 0;
    public static final int MAX_CRF = 51;
    @Nonnull
    public static final String DEFAULT_PRESET = "slow";
    /** x264 presets, fastest to slowest. */
    @Nonnull
    public static final List<String> PRESETS = Collections.unmodifiableList(Arrays.asList(
            "ultrafast", "superfast", "veryfast", "faster", "fast",
            "medium", "slow", "slower", "veryslow", "placebo"));

    /** Number of lines of ffmpeg output to keep to report on failure. */
    private static final int OUTPUT_TAIL_LINES = 20;

    /** Thrown when ffmpeg could not be run or returned an error. */
    public static class EncodeFailure extends Exception {
        private static final long serialVersionUID = 1L;

        private final int _iExitCode;
        @Nonnull
        private final String _sOutputTail;

        public EncodeFailure(int iExitCode, @Nonnull String sOutputTail) {
            super("ffmpeg exited with code " + iExitCode + "\n" + sOutputTail);
            _iExitCode = iExitCode;
            _sOutputTail = sOutputTail;
        }

        public int getExitCode() {
            return _iExitCode;
        }

        /** The last few lines ffmpeg printed. */
        public @Nonnull String getOutputTail() {
            return _sOutputTail;
        }
    }

    @Nonnull
    private final File _ffmpeg;
    private final int _iCrf;
    @Nonnull
    private final String _sPreset;

    public FfmpegEncoder(@Nonnull File ffmpeg, int iCrf, @Nonnull String sPreset) {
        if (!isValidCrf(iCrf))
            throw new IllegalArgumentException("Invalid crf " + iCrf);
        if (!PRESETS.contains(sPreset))
            throw new IllegalArgumentException("Invalid preset " + sPreset);
        _ffmpeg = ffmpeg;
        _iCrf = iCrf;
        _sPreset = sPreset;
    }

    public static boolean isValidCrf(int iCrf) {
        return iCrf >= MIN_CRF && iCrf <= MAX_CRF;
    }

    public @Nonnull File getFfmpeg() {
        return _ffmpeg;
    }

    /** Builds the ffmpeg command line to convert the intermediate file. */
    public @Nonnull List<String> buildCommand(@Nonnull File inputAvi, @Nonnull File outputMp4,
                                              boolean blnHasAudio)
    {
        List<String> cmd = new ArrayList<String>();
        cmd.add(_ffmpeg.getPath());
        Collections.addAll(cmd, "-hide_banner", "-nostdin", "-nostats", "-loglevel", "warning", "-y");
        Collections.addAll(cmd, "-i", inputAvi.getPath());
        Collections.addAll(cmd, "-map", "0:v:0");
        if (blnHasAudio)
            Collections.addAll(cmd, "-map", "0:a:0");
        // PSX MDEC is BT.601 YCbCr with JPEG-style (centered) chroma siting.
        // The intermediate file is already limited range.
        Collections.addAll(cmd, "-vf", "setparams=range=tv:colorspace=smpte170m"
                                    + ":color_primaries=smpte170m:color_trc=smpte170m"
                                    + ":chroma_location=center");
        Collections.addAll(cmd, "-c:v", "libx264",
                                "-preset", _sPreset,
                                "-tune", "film",
                                "-crf", String.valueOf(_iCrf),
                                // spend more bits on dark areas, which PSX video has a lot of
                                "-x264-params", "aq-mode=3",
                                "-pix_fmt", "yuv420p",
                                "-profile:v", "high");
        if (blnHasAudio) {
            // PSX audio rates (37800, 18900) are poorly supported by AAC players
            Collections.addAll(cmd, "-c:a", "aac", "-b:a", "192k", "-ar", "48000");
        }
        Collections.addAll(cmd, "-movflags", "+faststart");
        cmd.add(outputMp4.getPath());
        return cmd;
    }

    /** Runs ffmpeg to completion.
     * On failure the (possibly partial) output file is deleted. */
    public void encode(@Nonnull File inputAvi, @Nonnull File outputMp4, boolean blnHasAudio)
            throws EncodeFailure
    {
        List<String> cmd = buildCommand(inputAvi, outputMp4, blnHasAudio);
        LOG.log(Level.INFO, "Running {0}", cmd);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);

        LinkedList<String> tail = new LinkedList<String>();
        int iExitCode;
        try {
            Process p = pb.start();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), Charset.defaultCharset()));
            try {
                String sLine;
                while ((sLine = reader.readLine()) != null) {
                    LOG.log(Level.INFO, "ffmpeg: {0}", sLine);
                    tail.add(sLine);
                    if (tail.size() > OUTPUT_TAIL_LINES)
                        tail.removeFirst();
                }
            } finally {
                reader.close();
            }
            iExitCode = p.waitFor();
        } catch (IOException ex) {
            LOG.log(Level.SEVERE, "Error running ffmpeg", ex);
            deleteIfExists(outputMp4);
            throw new EncodeFailure(-1, String.valueOf(ex.getMessage()));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            deleteIfExists(outputMp4);
            throw new EncodeFailure(-1, "Interrupted");
        }

        if (iExitCode != 0) {
            deleteIfExists(outputMp4);
            StringBuilder sb = new StringBuilder();
            for (String sLine : tail) {
                if (sb.length() > 0)
                    sb.append('\n');
                sb.append(sLine);
            }
            throw new EncodeFailure(iExitCode, sb.toString());
        }
    }

    private static void deleteIfExists(@Nonnull File file) {
        if (file.exists() && !file.delete())
            LOG.log(Level.WARNING, "Unable to delete {0}", file);
    }

    // =========================================================================

    /** Finds the ffmpeg executable.
     * @param sUserPath Path the user provided, either the executable or the
     *                  directory containing it. If null, the PATH is searched.
     * @return null if not found. */
    public static @CheckForNull File locate(@CheckForNull String sUserPath) {
        boolean blnWindows = File.separatorChar == '\\';
        if (sUserPath != null) {
            File userFile = new File(sUserPath);
            if (userFile.isDirectory())
                return findInDirectory(userFile, blnWindows);
            else if (userFile.isFile())
                return userFile;
            else
                return null;
        }
        return findOnPath(System.getenv("PATH"), blnWindows);
    }

    /** Searches each directory in a PATH-style list for ffmpeg. */
    static @CheckForNull File findOnPath(@CheckForNull String sPathEnv, boolean blnWindows) {
        if (sPathEnv == null)
            return null;
        for (String sDir : sPathEnv.split(File.pathSeparator)) {
            // Windows PATH entries are sometimes quoted
            if (sDir.length() > 1 && sDir.startsWith("\"") && sDir.endsWith("\""))
                sDir = sDir.substring(1, sDir.length() - 1);
            if (sDir.isEmpty())
                continue;
            File found = findInDirectory(new File(sDir), blnWindows);
            if (found != null)
                return found;
        }
        return null;
    }

    static @CheckForNull File findInDirectory(@Nonnull File dir, boolean blnWindows) {
        File exe = new File(dir, blnWindows ? "ffmpeg.exe" : "ffmpeg");
        if (exe.isFile())
            return exe;
        return null;
    }
}
