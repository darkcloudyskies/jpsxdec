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

import java.util.Arrays;
import org.junit.Assert;
import org.junit.Test;

public class MdecDeblockerTest {

    private static final double DELTA = 1e-9;

    /** 2 blocks side by side, each a flat color. */
    private static double[] twoFlatBlocksWide(double dblLeft, double dblRight) {
        double[] adbl = new double[16 * 8];
        for (int y = 0; y < 8; y++) {
            Arrays.fill(adbl, y * 16, y * 16 + 8, dblLeft);
            Arrays.fill(adbl, y * 16 + 8, y * 16 + 16, dblRight);
        }
        return adbl;
    }

    @Test
    public void smallStepIsSmoothed() {
        double[] adbl = twoFlatBlocksWide(100, 103);
        new MdecDeblocker().deblock(adbl, 16, 8, new double[] {2, 2});

        for (int y = 0; y < 8; y++) {
            int iRow = y * 16;
            // the step at the edge is reduced
            Assert.assertTrue(adbl[iRow + 8] - adbl[iRow + 7] < 3);
            // becomes a smooth ramp that stays within the original range
            for (int x = 0; x < 15; x++) {
                Assert.assertTrue(adbl[iRow + x] <= adbl[iRow + x + 1] + DELTA);
                Assert.assertTrue(adbl[iRow + x] >= 100 - DELTA && adbl[iRow + x] <= 103 + DELTA);
            }
            // pixels far from the edge are untouched
            Assert.assertEquals(100, adbl[iRow], DELTA);
            Assert.assertEquals(103, adbl[iRow + 15], DELTA);
        }
    }

    @Test
    public void realEdgeIsKept() {
        double[] adbl = twoFlatBlocksWide(40, 90);
        double[] adblOrig = adbl.clone();
        new MdecDeblocker().deblock(adbl, 16, 8, new double[] {2, 2});
        Assert.assertArrayEquals(adblOrig, adbl, DELTA);
    }

    @Test
    public void stepTooBigForQuantizerIsKept() {
        // a step of 3 can't be explained by quantization at QP 1
        double[] adbl = twoFlatBlocksWide(100, 103);
        double[] adblOrig = adbl.clone();
        new MdecDeblocker().deblock(adbl, 16, 8, new double[] {1, 1});
        Assert.assertArrayEquals(adblOrig, adbl, DELTA);
    }

    @Test
    public void undecodedBlockIsSkipped() {
        double[] adbl = twoFlatBlocksWide(100, 103);
        double[] adblOrig = adbl.clone();
        new MdecDeblocker().deblock(adbl, 16, 8, new double[] {2, 0});
        Assert.assertArrayEquals(adblOrig, adbl, DELTA);
    }

    @Test
    public void horizontalEdge() {
        // 2 blocks stacked vertically
        double[] adbl = new double[8 * 16];
        Arrays.fill(adbl, 0, 64, 50);
        Arrays.fill(adbl, 64, 128, 53);
        new MdecDeblocker().deblock(adbl, 8, 16, new double[] {2, 2});
        for (int x = 0; x < 8; x++) {
            Assert.assertTrue(adbl[8 * 8 + x] - adbl[7 * 8 + x] < 3);
            Assert.assertEquals(50, adbl[x], DELTA);
            Assert.assertEquals(53, adbl[15 * 8 + x], DELTA);
        }
    }

    @Test
    public void edgeInTextureOnlyAdjustsEdgePixels() {
        // alternating texture on both sides with a small step at the edge:
        // too busy for smooth mode, so only v4 and v5 may change
        double[] adbl = new double[16 * 8];
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 16; x++)
                adbl[y * 16 + x] = (x < 8 ? 100 : 101) + ((x & 1) == 0 ? 0 : 6);
        }
        double[] adblOrig = adbl.clone();
        new MdecDeblocker().deblock(adbl, 16, 8, new double[] {4, 4});
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 16; x++) {
                if (x != 7 && x != 8)
                    Assert.assertEquals(adblOrig[y * 16 + x], adbl[y * 16 + x], DELTA);
            }
        }
    }
}
