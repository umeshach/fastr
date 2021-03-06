/*
 * Copyright (c) 2014, 2016, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.truffle.r.nodes.function;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.FrameSlot;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.frame.FrameSlotTypeException;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.r.runtime.RInternalError;

public final class TemporarySlotNode extends Node {

    private static final Object[] defaultTempIdentifiers = new Object[]{new Object(), new Object(), new Object(), new Object(), new Object(), new Object(), new Object(), new Object()};

    @CompilationFinal private FrameSlot tempSlot;
    private int tempIdentifier;
    private Object identifier;

    public void initialize(VirtualFrame frame, Object value, Runnable invalidate) {
        if (tempSlot == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            tempSlot = frame.getFrameDescriptor().findOrAddFrameSlot(identifier = defaultTempIdentifiers[0], FrameSlotKind.Object);
            invalidate.run();
        }
        try {
            if (frame.getObject(tempSlot) != null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                // keep the complete loop in the slow path
                do {
                    tempIdentifier++;
                    identifier = tempIdentifier < defaultTempIdentifiers.length ? defaultTempIdentifiers[tempIdentifier] : new Object();
                    tempSlot = frame.getFrameDescriptor().findOrAddFrameSlot(identifier, FrameSlotKind.Object);
                    invalidate.run();
                } while (frame.getObject(tempSlot) != null);
            }
        } catch (FrameSlotTypeException e) {
            CompilerDirectives.transferToInterpreter();
            throw RInternalError.shouldNotReachHere();
        }
        frame.setObject(tempSlot, value);
    }

    public void cleanup(VirtualFrame frame, Object object) {
        try {
            assert frame.getObject(tempSlot) == object;
        } catch (FrameSlotTypeException e) {
            throw RInternalError.shouldNotReachHere();
        }
        frame.setObject(tempSlot, null);
    }

    public Object getIdentifier() {
        return identifier;
    }
}
