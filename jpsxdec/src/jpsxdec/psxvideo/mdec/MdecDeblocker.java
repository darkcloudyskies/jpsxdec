/*
 * jPSXdec: PlayStation 1 Media Decoder/Converter in Java
 * Copyright (C) 2007-2026  Michael Sabin
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

package jpsxdec.psxvideo.mdec;

import javax.annotation.Nonnull;

/** Removes 8x8 block edges from a decoded image plane, using the
 * quantization scale of each block to decide how much can be smoothed.
 *<p>
 * This is the deblocking post-filter from MPEG-4 part 2
 * (ISO/IEC 14496-2 Annex F.3.1). PSX MDEC dequantizes the same way as
 * MPEG-1 intra blocks (coefficient * matrix * qscale / 8), so with the
 * default matrix (16 for the lowest AC coefficients) the MPEG-4 QP is the
 * block's qscale. An edge is only smoothed if the difference across it is
 * small enough to have been caused by quantization, so real edges are kept.
 *<p>
 * Each boundary is examined 10 pixels at a time, v0 to v9, with the block
 * edge between v4 and v5.
 * <ul>
 * <li>Smooth areas (most neighboring pixels nearly equal) get a low-pass
 *     filter over v1-v8 if the whole span is within 2*QP.
 * <li>Other areas only adjust v4 and v5 to reduce the step at the edge,
 *     limited by the local frequency content on either side.
 * </ul>
 * Pixel values are expected to have the same scale as 8-bit samples. */
public class MdecDeblocker {

    /** Neighboring pixels are "equal" if they differ by this much or less. */
    private static final double THR1 = 2;
    /** How many of the 9 neighbor pairs must be "equal" to use smooth mode. */
    private static final int THR2 = 6;
    private static final double[] SMOOTH_TAPS = {1, 1, 2, 2, 4, 2, 2, 1, 1};

    private final double[] _adblV = new double[10];
    private final double[] _adblPadded = new double[16];

    /** Deblocks a plane in place.
     * @param adblPlane    Pixels, row-major with a stride of iWidth.
     * @param iWidth       Plane width, multiple of 8.
     * @param iHeight      Plane height, multiple of 8.
     * @param adblBlockQp  Quantizer of each 8x8 block, row-major,
     *                     (iWidth/8) * (iHeight/8) entries.
     *                     0 means the block is left untouched. */
    public void deblock(@Nonnull double[] adblPlane, int iWidth, int iHeight,
                        @Nonnull double[] adblBlockQp)
    {
        final int iBlocksWide = iWidth / 8, iBlocksHigh = iHeight / 8;
        if (adblBlockQp.length < iBlocksWide * iBlocksHigh)
            throw new IllegalArgumentException("Not enough block QPs");

        // vertical block edges (filtered horizontally)
        for (int iBlkY = 0; iBlkY < iBlocksHigh; iBlkY++) {
            for (int iBlkX = 1; iBlkX < iBlocksWide; iBlkX++) {
                double dblQp = edgeQp(adblBlockQp[iBlkY * iBlocksWide + iBlkX - 1],
                                      adblBlockQp[iBlkY * iBlocksWide + iBlkX]);
                if (dblQp <= 0)
                    continue;
                int iEdgeX = iBlkX * 8;
                for (int iY = iBlkY * 8; iY < iBlkY * 8 + 8; iY++)
                    filterEdge(adblPlane, iY * iWidth + iEdgeX - 5, 1, dblQp);
            }
        }

        // horizontal block edges (filtered vertically)
        for (int iBlkY = 1; iBlkY < iBlocksHigh; iBlkY++) {
            for (int iBlkX = 0; iBlkX < iBlocksWide; iBlkX++) {
                double dblQp = edgeQp(adblBlockQp[(iBlkY - 1) * iBlocksWide + iBlkX],
                                      adblBlockQp[iBlkY * iBlocksWide + iBlkX]);
                if (dblQp <= 0)
                    continue;
                int iEdgeY = iBlkY * 8;
                for (int iX = iBlkX * 8; iX < iBlkX * 8 + 8; iX++)
                    filterEdge(adblPlane, (iEdgeY - 5) * iWidth + iX, iWidth, dblQp);
            }
        }
    }

    /** Edges touching a block that wasn't decoded (QP 0) are left alone,
     * otherwise the coarser of the two blocks decides. */
    private static double edgeQp(double dblQp1, double dblQp2) {
        if (dblQp1 <= 0 || dblQp2 <= 0)
            return 0;
        return Math.max(dblQp1, dblQp2);
    }

    /** Filters the 10 pixels starting at iStart, iStep apart. */
    private void filterEdge(@Nonnull double[] adblPlane, int iStart, int iStep, double dblQp) {
        final double[] v = _adblV;
        for (int i = 0, iOfs = iStart; i < 10; i++, iOfs += iStep)
            v[i] = adblPlane[iOfs];

        int iEqualCount = 0;
        for (int i = 0; i < 9; i++) {
            if (Math.abs(v[i] - v[i + 1]) <= THR1)
                iEqualCount++;
        }

        if (iEqualCount >= THR2)
            smoothMode(adblPlane, iStart, iStep, dblQp);
        else
            defaultMode(adblPlane, iStart, iStep, dblQp);
    }

    /** Low-pass v1-v8 if the whole span is flat enough. */
    private void smoothMode(@Nonnull double[] adblPlane, int iStart, int iStep, double dblQp) {
        final double[] v = _adblV;
        double dblMax = v[1], dblMin = v[1];
        for (int i = 2; i <= 8; i++) {
            dblMax = Math.max(dblMax, v[i]);
            dblMin = Math.min(dblMin, v[i]);
        }
        if (dblMax - dblMin >= 2 * dblQp)
            return;

        // The 9-tap filter over v1-v8 reaches v(-3) to v12. Everything
        // outside v1-v8 is replaced with the end pixel, or with v0/v9 if
        // those are close enough to belong to the same smooth area.
        double dblP0 = Math.abs(v[1] - v[0]) < dblQp ? v[0] : v[1];
        double dblP9 = Math.abs(v[8] - v[9]) < dblQp ? v[9] : v[8];
        final double[] p = _adblPadded; // p[m + 3] is pixel m, m = -3..12
        for (int m = -3; m <= 12; m++)
            p[m + 3] = m < 1 ? dblP0 : (m > 8 ? dblP9 : v[m]);

        for (int n = 1, iOfs = iStart + iStep; n <= 8; n++, iOfs += iStep) {
            double dblSum = 0;
            for (int k = -4; k <= 4; k++)
                dblSum += SMOOTH_TAPS[k + 4] * p[n + k + 3];
            adblPlane[iOfs] = dblSum / 16;
        }
    }

    /** Only reduce the step between v4 and v5. */
    private void defaultMode(@Nonnull double[] adblPlane, int iStart, int iStep, double dblQp) {
        final double[] v = _adblV;
        double dblA30 = (2 * v[3] - 5 * v[4] + 5 * v[5] - 2 * v[6]) / 8;
        if (Math.abs(dblA30) >= dblQp)
            return;
        double dblA31 = (2 * v[1] - 5 * v[2] + 5 * v[3] - 2 * v[4]) / 8;
        double dblA32 = (2 * v[5] - 5 * v[6] + 5 * v[7] - 2 * v[8]) / 8;
        double dblA30New = Math.signum(dblA30)
                         * Math.min(Math.abs(dblA30), Math.min(Math.abs(dblA31), Math.abs(dblA32)));
        double dblD = 5 * (dblA30New - dblA30) / 8;
        // d is limited to between 0 and half the step, so it never overshoots
        double dblHalfStep = (v[4] - v[5]) / 2;
        dblD = Math.max(Math.min(dblD, Math.max(0, dblHalfStep)), Math.min(0, dblHalfStep));
        adblPlane[iStart + 4 * iStep] = v[4] - dblD;
        adblPlane[iStart + 5 * iStep] = v[5] + dblD;
    }
}
