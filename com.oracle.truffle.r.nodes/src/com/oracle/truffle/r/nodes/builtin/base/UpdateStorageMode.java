/*
 * This material is distributed under the GNU General Public License
 * Version 2. You may review the terms of this license at
 * http://www.gnu.org/licenses/gpl-2.0.html
 *
 * Copyright (c) 2014, Purdue University
 * Copyright (c) 2014, Oracle and/or its affiliates
 *
 * All rights reserved.
 */

package com.oracle.truffle.r.nodes.builtin.base;

import static com.oracle.truffle.r.nodes.builtin.RBuiltinKind.PRIMITIVE;

import com.oracle.truffle.api.*;
import com.oracle.truffle.api.dsl.*;
import com.oracle.truffle.api.frame.*;
import com.oracle.truffle.r.nodes.*;
import com.oracle.truffle.r.nodes.binary.*;
import com.oracle.truffle.r.nodes.builtin.*;
import com.oracle.truffle.r.nodes.unary.*;
import com.oracle.truffle.r.runtime.*;
import com.oracle.truffle.r.runtime.data.*;
import com.oracle.truffle.r.runtime.data.RAttributes.RAttribute;

@RBuiltin(name = "storage.mode<-", kind = PRIMITIVE)
public abstract class UpdateStorageMode extends RBuiltinNode {

    @Child private Typeof typeof;
    @Child private CastTypeNode castTypeNode;
    @Child private IsFactor isFactor;

    @Specialization(order = 0, guards = {"!isReal", "!isSingle"})
    public Object update(VirtualFrame frame, final Object x, final String value) {
        controlVisibility();
        if (typeof == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            typeof = insert(TypeofFactory.create(new RNode[1], this.getBuiltin()));
        }
        String typeX = typeof.execute(frame, x);
        if (typeX.equals(value)) {
            return x;
        }
        if (isFactor == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            isFactor = insert(IsFactorFactory.create(new RNode[1], this.getBuiltin()));
        }
        if (isFactor.execute(frame, x) == RRuntime.LOGICAL_TRUE) {
            CompilerDirectives.transferToInterpreter();
            throw RError.getInvalidStorageModeUpdate(getEncapsulatingSourceSection());
        }
        if (castTypeNode == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            castTypeNode = insert(CastTypeNodeFactory.create(new RNode[2], this.getBuiltin()));
        }
        Object result = castTypeNode.execute(frame, x, value);
        if (result == null) {
            CompilerDirectives.transferToInterpreter();
            throw RError.getInvalidUnnamedValue(getEncapsulatingSourceSection());
        } else {
            if (x instanceof RAttributable && result instanceof RAttributable) {
                RAttributable rx = (RAttributable) x;
                RAttributes attrs = rx.getAttributes();
                if (attrs != null) {
                    RAttributable rresult = (RAttributable) result;
                    for (RAttribute attr : attrs) {
                        rresult.setAttr(attr.getName(), attr.getValue());
                    }
                }
            }
            return result;
        }
    }

    @SuppressWarnings("unused")
    @Specialization(order = 1, guards = "isReal")
    public Object updateReal(VirtualFrame frame, final Object x, final String value) {
        controlVisibility();
        CompilerDirectives.transferToInterpreter();
        throw RError.getDefunct(getEncapsulatingSourceSection(), RRuntime.REAL, RRuntime.TYPE_DOUBLE);
    }

    @SuppressWarnings("unused")
    @Specialization(order = 2, guards = "isSingle")
    public Object updateSingle(VirtualFrame frame, final Object x, final String value) {
        controlVisibility();
        CompilerDirectives.transferToInterpreter();
        throw RError.getDefunct(getEncapsulatingSourceSection(), RRuntime.SINGLE, "mode<-");
    }

    @SuppressWarnings("unused")
    @Specialization(order = 3)
    public Object update(VirtualFrame frame, final Object x, final Object value) {
        controlVisibility();
        CompilerDirectives.transferToInterpreter();
        throw RError.getValueNull(getEncapsulatingSourceSection());
    }

    @SuppressWarnings("unused")
    protected static boolean isReal(VirtualFrame frame, final Object x, final String value) {
        return value.equals(RRuntime.REAL);
    }

    @SuppressWarnings("unused")
    protected static boolean isSingle(VirtualFrame frame, final Object x, final String value) {
        return value.equals(RRuntime.SINGLE);
    }

}
