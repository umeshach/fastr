/*
 * This material is distributed under the GNU General Public License
 * Version 2. You may review the terms of this license at
 * http://www.gnu.org/licenses/gpl-2.0.html
 *
 * Copyright (c) 2014, Purdue University
 * Copyright (c) 2014, 2016, Oracle and/or its affiliates
 *
 * All rights reserved.
 */
package com.oracle.truffle.r.test.builtins;

import org.junit.Test;

import com.oracle.truffle.r.test.TestBase;

// Checkstyle: stop line length check
public class TestBuiltin_isOpen extends TestBase {

    @Test
    public void testisOpen1() {
        assertEval("argv <- list(structure(2L, class = c('terminal', 'connection')), 0L); .Internal(isOpen(argv[[1]], argv[[2]]))");
    }

    @Test
    public void testisOpen3() {
        assertEval("argv <- list(FALSE, 2L); .Internal(isOpen(argv[[1]], argv[[2]]))");
    }
}
