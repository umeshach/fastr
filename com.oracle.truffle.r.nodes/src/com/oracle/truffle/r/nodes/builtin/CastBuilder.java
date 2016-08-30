/*
 * Copyright (c) 2013, 2016, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.r.nodes.builtin;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.Function;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.profiles.ConditionProfile;
import com.oracle.truffle.r.nodes.binary.BoxPrimitiveNodeGen;
import com.oracle.truffle.r.nodes.builtin.ArgumentFilter.ArgumentTypeFilter;
import com.oracle.truffle.r.nodes.builtin.ArgumentFilter.ArgumentValueFilter;
import com.oracle.truffle.r.nodes.unary.BypassNode;
import com.oracle.truffle.r.nodes.unary.CastComplexNodeGen;
import com.oracle.truffle.r.nodes.unary.CastDoubleBaseNodeGen;
import com.oracle.truffle.r.nodes.unary.CastDoubleNodeGen;
import com.oracle.truffle.r.nodes.unary.CastIntegerBaseNodeGen;
import com.oracle.truffle.r.nodes.unary.CastIntegerNodeGen;
import com.oracle.truffle.r.nodes.unary.CastLogicalBaseNodeGen;
import com.oracle.truffle.r.nodes.unary.CastLogicalNodeGen;
import com.oracle.truffle.r.nodes.unary.CastNode;
import com.oracle.truffle.r.nodes.unary.CastRawNodeGen;
import com.oracle.truffle.r.nodes.unary.CastStringBaseNodeGen;
import com.oracle.truffle.r.nodes.unary.CastStringNodeGen;
import com.oracle.truffle.r.nodes.unary.CastToAttributableNodeGen;
import com.oracle.truffle.r.nodes.unary.CastToVectorNodeGen;
import com.oracle.truffle.r.nodes.unary.ChainedCastNode;
import com.oracle.truffle.r.nodes.unary.ConditionalMapNode;
import com.oracle.truffle.r.nodes.unary.FilterNode;
import com.oracle.truffle.r.nodes.unary.FindFirstNodeGen;
import com.oracle.truffle.r.nodes.unary.FirstBooleanNodeGen;
import com.oracle.truffle.r.nodes.unary.FirstIntNode;
import com.oracle.truffle.r.nodes.unary.FirstStringNode;
import com.oracle.truffle.r.nodes.unary.MapNode;
import com.oracle.truffle.r.nodes.unary.NonNANodeGen;
import com.oracle.truffle.r.runtime.RError;
import com.oracle.truffle.r.runtime.RError.Message;
import com.oracle.truffle.r.runtime.RInternalError;
import com.oracle.truffle.r.runtime.RRuntime;
import com.oracle.truffle.r.runtime.data.RAttributable;
import com.oracle.truffle.r.runtime.data.RComplex;
import com.oracle.truffle.r.runtime.data.RComplexVector;
import com.oracle.truffle.r.runtime.data.RDataFactory;
import com.oracle.truffle.r.runtime.data.RDoubleVector;
import com.oracle.truffle.r.runtime.data.RIntVector;
import com.oracle.truffle.r.runtime.data.RList;
import com.oracle.truffle.r.runtime.data.RLogicalVector;
import com.oracle.truffle.r.runtime.data.RMissing;
import com.oracle.truffle.r.runtime.data.RNull;
import com.oracle.truffle.r.runtime.data.RRaw;
import com.oracle.truffle.r.runtime.data.RStringVector;
import com.oracle.truffle.r.runtime.data.model.RAbstractComplexVector;
import com.oracle.truffle.r.runtime.data.model.RAbstractDoubleVector;
import com.oracle.truffle.r.runtime.data.model.RAbstractIntVector;
import com.oracle.truffle.r.runtime.data.model.RAbstractListVector;
import com.oracle.truffle.r.runtime.data.model.RAbstractLogicalVector;
import com.oracle.truffle.r.runtime.data.model.RAbstractRawVector;
import com.oracle.truffle.r.runtime.data.model.RAbstractStringVector;
import com.oracle.truffle.r.runtime.data.model.RAbstractVector;
import com.oracle.truffle.r.runtime.nodes.RBaseNode;
import com.oracle.truffle.r.runtime.ops.na.NACheck;

public final class CastBuilder {

    private static final CastNodeFactory[] EMPTY_CAST_FACT_ARRAY = new CastNodeFactory[0];
    private static final PipelineConfigBuilder[] EMPTY_PIPELINE_CFG_BUILDER_ARRAY = new PipelineConfigBuilder[0];

    private final RBuiltinNode builtinNode;

    private CastNodeFactory[] castFactories = EMPTY_CAST_FACT_ARRAY;
    private PipelineConfigBuilder[] pipelineCfgBuilders = EMPTY_PIPELINE_CFG_BUILDER_ARRAY;
    private CastNode[] castsWrapped = null;

    public CastBuilder(RBuiltinNode builtinNode) {
        this.builtinNode = builtinNode;
    }

    public CastBuilder() {
        this(null);
    }

    @FunctionalInterface
    public interface CastNodeFactory {
        CastNode create();
    }

    private CastBuilder insert(int index, final CastNodeFactory castNodeFactory) {
        if (index >= castFactories.length) {
            castFactories = Arrays.copyOf(castFactories, index + 1);
        }
        final CastNodeFactory cnf = castFactories[index];
        if (cnf == null) {
            castFactories[index] = castNodeFactory;
        } else {
            castFactories[index] = () -> new ChainedCastNode(cnf, castNodeFactory);
        }
        return this;
    }

    public CastNode[] getCasts() {
        if (castsWrapped == null) {
            int len = Math.max(castFactories.length, pipelineCfgBuilders.length);
            castsWrapped = new CastNode[len];
            for (int i = 0; i < len; i++) {
                CastNodeFactory cnf = i < castFactories.length ? castFactories[i] : null;
                CastNode cn = cnf == null ? null : cnf.create();
                if (i < pipelineCfgBuilders.length && pipelineCfgBuilders[i] != null) {
                    castsWrapped[i] = BypassNode.create(pipelineCfgBuilders[i], cnf == null ? null : cn);
                } else {
                    castsWrapped[i] = cn;
                }
            }
        }

        return castsWrapped;
    }

    public CastBuilder toAttributable(int index, boolean preserveNames, boolean dimensionsPreservation, boolean attrPreservation) {
        return insert(index, () -> CastToAttributableNodeGen.create(preserveNames, dimensionsPreservation, attrPreservation));
    }

    public CastBuilder toVector(int index) {
        return insert(index, () -> CastToVectorNodeGen.create(false));
    }

    public CastBuilder toVector(int index, boolean preserveNonVector) {
        return insert(index, () -> CastToVectorNodeGen.create(preserveNonVector));
    }

    public CastBuilder toInteger(int index) {
        return toInteger(index, false, false, false);
    }

    public CastBuilder toInteger(int index, boolean preserveNames, boolean dimensionsPreservation, boolean attrPreservation) {
        return insert(index, () -> CastIntegerNodeGen.create(preserveNames, dimensionsPreservation, attrPreservation));
    }

    public CastBuilder toDouble(int index) {
        return toDouble(index, false, false, false);
    }

    public CastBuilder toDouble(int index, boolean preserveNames, boolean dimensionsPreservation, boolean attrPreservation) {
        return insert(index, () -> CastDoubleNodeGen.create(preserveNames, dimensionsPreservation, attrPreservation));
    }

    public CastBuilder toLogical(int index) {
        return insert(index, () -> CastLogicalNodeGen.create(false, false, false));
    }

    public CastBuilder toCharacter(int index) {
        return insert(index, () -> CastStringNodeGen.create(false, false, false));
    }

    public CastBuilder toCharacter(int index, boolean preserveNames, boolean dimensionsPreservation, boolean attrPreservation) {
        return insert(index, () -> CastStringNodeGen.create(preserveNames, dimensionsPreservation, attrPreservation));
    }

    public CastBuilder toComplex(int index) {
        return insert(index, () -> CastComplexNodeGen.create(false, false, false));
    }

    public CastBuilder toRaw(int index) {
        return insert(index, () -> CastRawNodeGen.create(false, false, false));
    }

    public CastBuilder boxPrimitive(int index) {
        return insert(index, () -> BoxPrimitiveNodeGen.create());
    }

    public CastBuilder custom(int index, CastNodeFactory castNodeFactory) {
        return insert(index, castNodeFactory);
    }

    public CastBuilder firstIntegerWithWarning(int index, int intNa, String name) {
        insert(index, () -> CastIntegerNodeGen.create(false, false, false));
        return insert(index, () -> FirstIntNode.createWithWarning(RError.Message.FIRST_ELEMENT_USED, name, intNa));
    }

    public CastBuilder firstIntegerWithError(int index, RError.Message error, String name) {
        insert(index, () -> CastIntegerNodeGen.create(false, false, false));
        return insert(index, () -> FirstIntNode.createWithError(error, name));
    }

    public CastBuilder firstStringWithError(int index, RError.Message error, String name) {
        return insert(index, () -> FirstStringNode.createWithError(error, name));
    }

    public CastBuilder firstBoolean(int index) {
        return insert(index, () -> FirstBooleanNodeGen.create(null));
    }

    public CastBuilder firstBoolean(int index, String invalidValueName) {
        return insert(index, () -> FirstBooleanNodeGen.create(invalidValueName));
    }

    public CastBuilder firstLogical(int index) {
        arg(index).asLogicalVector().findFirst(RRuntime.LOGICAL_NA);
        return this;
    }

    public PreinitialPhaseBuilder<Object> arg(int argumentIndex, String argumentName) {
        return new ArgCastBuilderFactoryImpl(argumentIndex, argumentName).newPreinitialPhaseBuilder();
    }

    public PreinitialPhaseBuilder<Object> arg(int argumentIndex) {
        return arg(argumentIndex, builtinNode == null ? null : builtinNode.getRBuiltin().parameterNames()[argumentIndex]);
    }

    public PreinitialPhaseBuilder<Object> arg(String argumentName) {
        return arg(getArgumentIndex(argumentName), argumentName);
    }

    private int getArgumentIndex(String argumentName) {
        if (builtinNode == null) {
            throw new IllegalArgumentException("No builtin node associated with cast builder");
        }
        String[] parameterNames = builtinNode.getRBuiltin().parameterNames();
        for (int i = 0; i < parameterNames.length; i++) {
            if (argumentName.equals(parameterNames[i])) {
                return i;
            }
        }
        CompilerDirectives.transferToInterpreter();
        throw RInternalError.shouldNotReachHere(String.format("Argument %s not found in builtin %s", argumentName, builtinNode.getRBuiltin().name()));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Object[] substituteArgPlaceholder(Object arg, Object[] messageArgs) {
        Object[] newMsgArgs = Arrays.copyOf(messageArgs, messageArgs.length);

        for (int i = 0; i < messageArgs.length; i++) {
            final Object msgArg = messageArgs[i];
            if (msgArg instanceof Function) {
                newMsgArgs[i] = ((Function) msgArg).apply(arg);
            }
        }

        return newMsgArgs;
    }

    public interface PredefFilters {

        <T> ValuePredicateArgumentFilter<T> sameAs(T x);

        <T> ValuePredicateArgumentFilter<T> equalTo(T x);

        <T extends RAbstractVector> VectorPredicateArgumentFilter<T> notEmpty();

        <T extends RAbstractVector> VectorPredicateArgumentFilter<T> singleElement();

        <T extends RAbstractVector, R extends T> VectorPredicateArgumentFilter<T> size(int s);

        VectorPredicateArgumentFilter<RAbstractStringVector> elementAt(int index, String value);

        VectorPredicateArgumentFilter<RAbstractIntVector> elementAt(int index, int value);

        VectorPredicateArgumentFilter<RAbstractDoubleVector> elementAt(int index, double value);

        VectorPredicateArgumentFilter<RAbstractComplexVector> elementAt(int index, RComplex value);

        VectorPredicateArgumentFilter<RAbstractLogicalVector> elementAt(int index, byte value);

        <T extends RAbstractVector> VectorPredicateArgumentFilter<T> matrix();

        <T extends RAbstractVector> VectorPredicateArgumentFilter<T> squareMatrix();

        <T extends RAbstractVector> VectorPredicateArgumentFilter<T> dimEq(int dim, int x);

        <T extends RAbstractVector> VectorPredicateArgumentFilter<T> dimGt(int dim, int x);

        ValuePredicateArgumentFilter<Boolean> trueValue();

        ValuePredicateArgumentFilter<Boolean> falseValue();

        ValuePredicateArgumentFilter<Byte> logicalTrue();

        ValuePredicateArgumentFilter<Byte> logicalFalse();

        ValuePredicateArgumentFilter<Integer> intNA();

        ValuePredicateArgumentFilter<Byte> logicalNA();

        ValuePredicateArgumentFilter<Double> doubleNA();

        ValuePredicateArgumentFilter<Double> isFractional();

        ValuePredicateArgumentFilter<Double> isFinite();

        ValuePredicateArgumentFilter<String> stringNA();

        ValuePredicateArgumentFilter<Integer> eq(int x);

        ValuePredicateArgumentFilter<Double> eq(double x);

        ValuePredicateArgumentFilter<String> eq(String x);

        ValuePredicateArgumentFilter<Integer> gt(int x);

        ValuePredicateArgumentFilter<Double> gt(double x);

        ValuePredicateArgumentFilter<Double> gte(double x);

        ValuePredicateArgumentFilter<Integer> lt(int x);

        ValuePredicateArgumentFilter<Double> lt(double x);

        ValuePredicateArgumentFilter<Double> lte(double x);

        ValuePredicateArgumentFilter<String> length(int l);

        ValuePredicateArgumentFilter<String> lengthGt(int l);

        ValuePredicateArgumentFilter<String> lengthLt(int l);

        <R> TypePredicateArgumentFilter<Object, R> instanceOf(Class<R> cls);

        <R extends RAbstractIntVector> TypePredicateArgumentFilter<Object, R> integerValue();

        <R extends RAbstractStringVector> TypePredicateArgumentFilter<Object, R> stringValue();

        <R extends RAbstractDoubleVector> TypePredicateArgumentFilter<Object, R> doubleValue();

        <R extends RAbstractLogicalVector> TypePredicateArgumentFilter<Object, R> logicalValue();

        <R extends RAbstractComplexVector> TypePredicateArgumentFilter<Object, R> complexValue();

        <R extends RAbstractRawVector> TypePredicateArgumentFilter<Object, R> rawValue();

        <R> TypePredicateArgumentFilter<Object, R> anyValue();

        TypePredicateArgumentFilter<Object, String> scalarStringValue();

        TypePredicateArgumentFilter<Object, Integer> scalarIntegerValue();

        TypePredicateArgumentFilter<Object, Double> scalarDoubleValue();

        TypePredicateArgumentFilter<Object, Byte> scalarLogicalValue();

        TypePredicateArgumentFilter<Object, RComplex> scalarComplexValue();

    }

    public interface PredefMappers {
        ValuePredicateArgumentMapper<Byte, Boolean> toBoolean();

        ValuePredicateArgumentMapper<Double, Integer> doubleToInt();

        ValuePredicateArgumentMapper<String, Integer> charAt0(int defaultValue);

        <T> ValuePredicateArgumentMapper<T, RNull> nullConstant();

        <T> ValuePredicateArgumentMapper<T, RMissing> missingConstant();

        <T> ValuePredicateArgumentMapper<T, String> constant(String s);

        <T> ValuePredicateArgumentMapper<T, Integer> constant(int i);

        <T> ValuePredicateArgumentMapper<T, Double> constant(double d);

        <T> ValuePredicateArgumentMapper<T, Byte> constant(byte l);

        <T> ValuePredicateArgumentMapper<T, RIntVector> emptyIntegerVector();

        <T> ValuePredicateArgumentMapper<T, RDoubleVector> emptyDoubleVector();

        <T> ValuePredicateArgumentMapper<T, RLogicalVector> emptyLogicalVector();

        <T> ValuePredicateArgumentMapper<T, RComplexVector> emptyComplexVector();

        <T> ValuePredicateArgumentMapper<T, RStringVector> emptyStringVector();

        <T> ValuePredicateArgumentMapper<T, RList> emptyList();

        @Deprecated
        <T> ArgumentMapper<T, T> defaultValue(T defVal);

    }

    public static final class DefaultPredefFilters implements PredefFilters {

        @Override
        public <T> ValuePredicateArgumentFilter<T> sameAs(T x) {
            return ValuePredicateArgumentFilter.fromLambda(arg -> arg == x);
        }

        @Override
        public <T> ValuePredicateArgumentFilter<T> equalTo(T x) {
            return ValuePredicateArgumentFilter.fromLambda(arg -> Objects.equals(arg, x));
        }

        @Override
        public <T extends RAbstractVector> VectorPredicateArgumentFilter<T> notEmpty() {
            return new VectorPredicateArgumentFilter<>(x -> x.getLength() > 0, false);
        }

        @Override
        public <T extends RAbstractVector> VectorPredicateArgumentFilter<T> singleElement() {
            return new VectorPredicateArgumentFilter<>(x -> x.getLength() == 1, false);
        }

        @Override
        public <T extends RAbstractVector, R extends T> VectorPredicateArgumentFilter<T> size(int s) {
            return new VectorPredicateArgumentFilter<>(x -> x.getLength() == s, false);
        }

        @Override
        public VectorPredicateArgumentFilter<RAbstractStringVector> elementAt(int index, String value) {
            return new VectorPredicateArgumentFilter<>(x -> index < x.getLength() && value.equals(x.getDataAtAsObject(index)), false);
        }

        @Override
        public VectorPredicateArgumentFilter<RAbstractIntVector> elementAt(int index, int value) {
            return new VectorPredicateArgumentFilter<>(x -> index < x.getLength() && value == (int) x.getDataAtAsObject(index), false);
        }

        @Override
        public VectorPredicateArgumentFilter<RAbstractDoubleVector> elementAt(int index, double value) {
            return new VectorPredicateArgumentFilter<>(x -> index < x.getLength() && value == (double) x.getDataAtAsObject(index), false);
        }

        @Override
        public VectorPredicateArgumentFilter<RAbstractComplexVector> elementAt(int index, RComplex value) {
            return new VectorPredicateArgumentFilter<>(x -> index < x.getLength() && value.equals(x.getDataAtAsObject(index)), false);
        }

        @Override
        public VectorPredicateArgumentFilter<RAbstractLogicalVector> elementAt(int index, byte value) {
            return new VectorPredicateArgumentFilter<>(x -> index < x.getLength() && value == (byte) (x.getDataAtAsObject(index)), false);
        }

        @Override
        public <T extends RAbstractVector> VectorPredicateArgumentFilter<T> matrix() {
            return new VectorPredicateArgumentFilter<>(x -> x.isMatrix(), false);
        }

        @Override
        public <T extends RAbstractVector> VectorPredicateArgumentFilter<T> squareMatrix() {
            return new VectorPredicateArgumentFilter<>(x -> x.isMatrix() && x.getDimensions()[0] == x.getDimensions()[1], false);
        }

        @Override
        public <T extends RAbstractVector> VectorPredicateArgumentFilter<T> dimEq(int dim, int x) {
            return new VectorPredicateArgumentFilter<>(v -> v.isMatrix() && v.getDimensions().length > dim && v.getDimensions()[dim] == x, false);
        }

        @Override
        public <T extends RAbstractVector> VectorPredicateArgumentFilter<T> dimGt(int dim, int x) {
            return new VectorPredicateArgumentFilter<>(v -> v.isMatrix() && v.getDimensions().length > dim && v.getDimensions()[dim] > x, false);
        }

        @Override
        public ValuePredicateArgumentFilter<Boolean> trueValue() {
            return ValuePredicateArgumentFilter.fromLambda(x -> x);
        }

        @Override
        public ValuePredicateArgumentFilter<Boolean> falseValue() {
            return ValuePredicateArgumentFilter.fromLambda(x -> x);
        }

        @Override
        public ValuePredicateArgumentFilter<Byte> logicalTrue() {
            return ValuePredicateArgumentFilter.fromLambda(x -> RRuntime.LOGICAL_TRUE == x);
        }

        @Override
        public ValuePredicateArgumentFilter<Byte> logicalFalse() {
            return ValuePredicateArgumentFilter.fromLambda(x -> RRuntime.LOGICAL_FALSE == x);
        }

        @Override
        public ValuePredicateArgumentFilter<Integer> intNA() {
            return ValuePredicateArgumentFilter.fromLambda((Integer x) -> RRuntime.isNA(x));
        }

        @Override
        public ValuePredicateArgumentFilter<Byte> logicalNA() {
            return ValuePredicateArgumentFilter.fromLambda((Byte x) -> RRuntime.isNA(x));
        }

        @Override
        public ValuePredicateArgumentFilter<Double> doubleNA() {
            return ValuePredicateArgumentFilter.fromLambda((Double x) -> RRuntime.isNAorNaN(x));
        }

        @Override
        public ValuePredicateArgumentFilter<Double> isFractional() {
            return ValuePredicateArgumentFilter.fromLambda((Double x) -> !RRuntime.isNAorNaN(x) && !Double.isInfinite(x) && x != Math.floor(x));
        }

        @Override
        public ValuePredicateArgumentFilter<Double> isFinite() {
            return ValuePredicateArgumentFilter.fromLambda((Double x) -> !Double.isInfinite(x));
        }

        @Override
        public ValuePredicateArgumentFilter<String> stringNA() {
            return ValuePredicateArgumentFilter.fromLambda((String x) -> RRuntime.isNA(x));
        }

        @Override
        public ValuePredicateArgumentFilter<Integer> eq(int x) {
            return ValuePredicateArgumentFilter.fromLambda((Integer arg) -> arg != null && arg.intValue() == x);
        }

        @Override
        public ValuePredicateArgumentFilter<Double> eq(double x) {
            return ValuePredicateArgumentFilter.fromLambda((Double arg) -> arg != null && arg.doubleValue() == x);
        }

        @Override
        public ValuePredicateArgumentFilter<String> eq(String x) {
            return ValuePredicateArgumentFilter.fromLambda((String arg) -> arg != null && arg.equals(x));
        }

        @Override
        public ValuePredicateArgumentFilter<Integer> gt(int x) {
            return ValuePredicateArgumentFilter.fromLambda((Integer arg) -> arg != null && arg > x);
        }

        @Override
        public ValuePredicateArgumentFilter<Double> gt(double x) {
            return ValuePredicateArgumentFilter.fromLambda((Double arg) -> arg != null && arg > x);
        }

        @Override
        public ValuePredicateArgumentFilter<Double> gte(double x) {
            return ValuePredicateArgumentFilter.fromLambda((Double arg) -> arg != null && arg >= x);
        }

        @Override
        public ValuePredicateArgumentFilter<Integer> lt(int x) {
            return ValuePredicateArgumentFilter.fromLambda((Integer arg) -> arg != null && arg < x);
        }

        @Override
        public ValuePredicateArgumentFilter<Double> lt(double x) {
            return ValuePredicateArgumentFilter.fromLambda((Double arg) -> arg < x);
        }

        @Override
        public ValuePredicateArgumentFilter<Double> lte(double x) {
            return ValuePredicateArgumentFilter.fromLambda((Double arg) -> arg <= x);
        }

        @Override
        public ValuePredicateArgumentFilter<String> length(int l) {
            return ValuePredicateArgumentFilter.fromLambda((String arg) -> arg != null && arg.length() == l);
        }

        @Override
        public ValuePredicateArgumentFilter<String> lengthGt(int l) {
            return ValuePredicateArgumentFilter.fromLambda((String arg) -> arg != null && arg.length() > l);
        }

        @Override
        public ValuePredicateArgumentFilter<String> lengthLt(int l) {
            return ValuePredicateArgumentFilter.fromLambda((String arg) -> arg != null && arg.length() < l);
        }

        @Override
        public <R> TypePredicateArgumentFilter<Object, R> instanceOf(Class<R> cls) {
            assert cls != RNull.class : "cannot handle RNull.class with an isNullable=false filter";
            return TypePredicateArgumentFilter.fromLambda(x -> cls.isInstance(x));
        }

        @Override
        public <R extends RAbstractIntVector> TypePredicateArgumentFilter<Object, R> integerValue() {
            return TypePredicateArgumentFilter.fromLambda(x -> x instanceof Integer || x instanceof RAbstractIntVector);
        }

        @Override
        public <R extends RAbstractStringVector> TypePredicateArgumentFilter<Object, R> stringValue() {
            return TypePredicateArgumentFilter.fromLambda(x -> x instanceof String || x instanceof RAbstractStringVector);
        }

        @Override
        public <R extends RAbstractDoubleVector> TypePredicateArgumentFilter<Object, R> doubleValue() {
            return TypePredicateArgumentFilter.fromLambda(x -> x instanceof Double || x instanceof RAbstractDoubleVector);
        }

        @Override
        public <R extends RAbstractLogicalVector> TypePredicateArgumentFilter<Object, R> logicalValue() {
            return TypePredicateArgumentFilter.fromLambda(x -> x instanceof Byte || x instanceof RAbstractLogicalVector);
        }

        @Override
        public <R extends RAbstractComplexVector> TypePredicateArgumentFilter<Object, R> complexValue() {
            return TypePredicateArgumentFilter.fromLambda(x -> x instanceof RAbstractComplexVector);
        }

        @Override
        public <R extends RAbstractRawVector> TypePredicateArgumentFilter<Object, R> rawValue() {
            return TypePredicateArgumentFilter.fromLambda(x -> x instanceof RRaw || x instanceof RAbstractRawVector);
        }

        @Override
        public <R> TypePredicateArgumentFilter<Object, R> anyValue() {
            return TypePredicateArgumentFilter.fromLambda(x -> true);
        }

        /**
         * @deprecated tests for scalar types are dangerous
         */
        @Deprecated
        @Override
        public TypePredicateArgumentFilter<Object, String> scalarStringValue() {
            return TypePredicateArgumentFilter.fromLambda(x -> x instanceof String);
        }

        /**
         * @deprecated tests for scalar types are dangerous
         */
        @Deprecated
        @Override
        public TypePredicateArgumentFilter<Object, Integer> scalarIntegerValue() {
            return TypePredicateArgumentFilter.fromLambda(x -> x instanceof Integer);
        }

        /**
         * @deprecated tests for scalar types are dangerous
         */
        @Deprecated
        @Override
        public TypePredicateArgumentFilter<Object, Double> scalarDoubleValue() {
            return TypePredicateArgumentFilter.fromLambda(x -> x instanceof Double);
        }

        /**
         * @deprecated tests for scalar types are dangerous
         */
        @Deprecated
        @Override
        public TypePredicateArgumentFilter<Object, Byte> scalarLogicalValue() {
            return TypePredicateArgumentFilter.fromLambda(x -> x instanceof Byte);
        }

        /**
         * @deprecated tests for scalar types are dangerous
         */
        @Deprecated
        @Override
        public TypePredicateArgumentFilter<Object, RComplex> scalarComplexValue() {
            return TypePredicateArgumentFilter.fromLambda(x -> x instanceof RComplex);
        }

    }

    public static final class DefaultPredefMappers implements PredefMappers {

        @Override
        public ValuePredicateArgumentMapper<Byte, Boolean> toBoolean() {
            return ValuePredicateArgumentMapper.fromLambda(x -> RRuntime.fromLogical(x));
        }

        @Override
        public ValuePredicateArgumentMapper<Double, Integer> doubleToInt() {
            final NACheck naCheck = NACheck.create();
            return ValuePredicateArgumentMapper.fromLambda(x -> {
                naCheck.enable(x);
                return naCheck.convertDoubleToInt(x);
            });
        }

        @Override
        public ValuePredicateArgumentMapper<String, Integer> charAt0(int defaultValue) {
            final ConditionProfile profile = ConditionProfile.createBinaryProfile();
            final ConditionProfile profile2 = ConditionProfile.createBinaryProfile();
            return ValuePredicateArgumentMapper.fromLambda(x -> {
                if (profile.profile(x == null || x.isEmpty())) {
                    return defaultValue;
                } else {
                    if (profile2.profile(x == RRuntime.STRING_NA)) {
                        return RRuntime.INT_NA;
                    } else {
                        return (int) x.charAt(0);
                    }
                }
            });
        }

        @Override
        public <T> ValuePredicateArgumentMapper<T, RNull> nullConstant() {
            return ValuePredicateArgumentMapper.fromLambda(x -> RNull.instance);
        }

        @Override
        public <T> ValuePredicateArgumentMapper<T, RMissing> missingConstant() {
            return ValuePredicateArgumentMapper.fromLambda(x -> RMissing.instance);
        }

        @Override
        public <T> ValuePredicateArgumentMapper<T, String> constant(String s) {
            return ValuePredicateArgumentMapper.fromLambda((T x) -> s);
        }

        @Override
        public <T> ValuePredicateArgumentMapper<T, Integer> constant(int i) {
            return ValuePredicateArgumentMapper.fromLambda(x -> i);
        }

        @Override
        public <T> ValuePredicateArgumentMapper<T, Double> constant(double d) {
            return ValuePredicateArgumentMapper.fromLambda(x -> d);
        }

        @Override
        public <T> ValuePredicateArgumentMapper<T, Byte> constant(byte l) {
            return ValuePredicateArgumentMapper.fromLambda(x -> l);
        }

        @Override
        public <T> ValuePredicateArgumentMapper<T, RIntVector> emptyIntegerVector() {
            return ValuePredicateArgumentMapper.fromLambda(x -> RDataFactory.createEmptyIntVector());
        }

        @Override
        public <T> ValuePredicateArgumentMapper<T, RDoubleVector> emptyDoubleVector() {
            return ValuePredicateArgumentMapper.fromLambda(x -> RDataFactory.createEmptyDoubleVector());
        }

        @Override
        public <T> ValuePredicateArgumentMapper<T, RLogicalVector> emptyLogicalVector() {
            return ValuePredicateArgumentMapper.fromLambda(x -> RDataFactory.createEmptyLogicalVector());
        }

        @Override
        public <T> ValuePredicateArgumentMapper<T, RComplexVector> emptyComplexVector() {
            return ValuePredicateArgumentMapper.fromLambda(x -> RDataFactory.createEmptyComplexVector());
        }

        @Override
        public <T> ValuePredicateArgumentMapper<T, RStringVector> emptyStringVector() {
            return ValuePredicateArgumentMapper.fromLambda(x -> RDataFactory.createEmptyStringVector());
        }

        @Override
        public <T> ValuePredicateArgumentMapper<T, RList> emptyList() {
            return ValuePredicateArgumentMapper.fromLambda(x -> RDataFactory.createList());
        }

        @Override
        @Deprecated
        public <T> ArgumentMapper<T, T> defaultValue(T defVal) {

            assert (defVal != null);

            return new ArgumentMapper<T, T>() {

                final ConditionProfile profile = ConditionProfile.createBinaryProfile();

                @Override
                public T map(T arg) {
                    assert arg != null;
                    if (profile.profile(arg == RNull.instance)) {
                        return defVal;
                    } else {
                        return arg;
                    }
                }

            };
        }
    }

    public static final class Predef {

        private static PredefFilters predefFilters = new DefaultPredefFilters();
        private static PredefMappers predefMappers = new DefaultPredefMappers();

        /**
         * Invoked from tests only.
         *
         * @param pf
         */
        public static void setPredefFilters(PredefFilters pf) {
            predefFilters = pf;
        }

        /**
         * Invoked from tests only.
         *
         * @param pm
         */
        public static void setPredefMappers(PredefMappers pm) {
            predefMappers = pm;
        }

        private static PredefFilters predefFilters() {
            return predefFilters;
        }

        private static PredefMappers predefMappers() {
            return predefMappers;
        }

        public static <T> ArgumentValueFilter<T> not(ArgumentValueFilter<T> filter) {
            return filter.not();
        }

        public static <T> ArgumentValueFilter<T> and(ArgumentValueFilter<T> filter1, ArgumentValueFilter<T> filter2) {
            return filter1.and(filter2);
        }

        public static <T> ArgumentValueFilter<T> or(ArgumentValueFilter<T> filter1, ArgumentValueFilter<T> filter2) {
            return filter1.or(filter2);
        }

        public static <T> Function<ArgCastBuilder<T, ?>, CastNode> mustBe(ArgumentFilter<?, ?> argFilter, RBaseNode callObj, boolean boxPrimitives, RError.Message message, Object... messageArgs) {
            return phaseBuilder -> FilterNode.create(argFilter, false, callObj, message, messageArgs, boxPrimitives);
        }

        public static <T> Function<ArgCastBuilder<T, ?>, CastNode> mustBe(ArgumentFilter<?, ?> argFilter, boolean boxPrimitives) {
            return phaseBuilder -> FilterNode.create(argFilter, false, phaseBuilder.state().defaultError().callObj, phaseBuilder.state().defaultError().message,
                            phaseBuilder.state().defaultError().args, boxPrimitives);
        }

        public static <T> Function<ArgCastBuilder<T, ?>, CastNode> shouldBe(ArgumentFilter<?, ?> argFilter, RBaseNode callObj, boolean boxPrimitives, RError.Message message, Object... messageArgs) {
            return phaseBuilder -> FilterNode.create(argFilter, true, callObj, message, messageArgs, boxPrimitives);
        }

        public static <T> Function<ArgCastBuilder<T, ?>, CastNode> shouldBe(ArgumentFilter<?, ?> argFilter, boolean boxPrimitives) {
            return phaseBuilder -> FilterNode.create(argFilter, true, phaseBuilder.state().defaultError().callObj, phaseBuilder.state().defaultError().message,
                            phaseBuilder.state().defaultError().args, boxPrimitives);
        }

        public static <T> Function<ArgCastBuilder<T, ?>, CastNode> map(ArgumentMapper<?, ?> mapper) {
            return phaseBuilder -> MapNode.create(mapper);
        }

        public static <T> Function<ArgCastBuilder<T, ?>, CastNode> mapIf(ArgumentFilter<?, ?> filter, Function<ArgCastBuilder<T, ?>, CastNode> trueBranchFactory,
                        Function<ArgCastBuilder<T, ?>, CastNode> falseBranchFactory) {
            return phaseBuilder -> ConditionalMapNode.create(filter, trueBranchFactory.apply(phaseBuilder), falseBranchFactory.apply(phaseBuilder));
        }

        public static <T> Function<ArgCastBuilder<T, ?>, CastNode> mapIf(ArgumentFilter<?, ?> filter, Function<ArgCastBuilder<T, ?>, CastNode> trueBranchFactory) {
            return phaseBuilder -> ConditionalMapNode.create(filter, trueBranchFactory.apply(phaseBuilder), null);
        }

        public static <T> ChainBuilder<T> chain(CastNode firstCast) {
            return new ChainBuilder<>(pb -> firstCast);
        }

        public static <T> ChainBuilder<T> chain(Function<ArgCastBuilder<T, ?>, CastNode> firstCastNodeFactory) {
            return new ChainBuilder<>(firstCastNodeFactory);
        }

        public static <T> Function<ArgCastBuilder<T, ?>, CastNode> asInteger() {
            return phaseBuilder -> CastIntegerBaseNodeGen.create(false, false, false);
        }

        public static <T> Function<ArgCastBuilder<T, ?>, CastNode> asIntegerVector() {
            return phaseBuilder -> CastIntegerNodeGen.create(false, false, false);
        }

        public static <T> Function<ArgCastBuilder<T, ?>, CastNode> asIntegerVector(boolean preserveNames, boolean preserveDimensions, boolean preserveAttributes) {
            return phaseBuilder -> CastIntegerNodeGen.create(preserveNames, preserveDimensions, preserveAttributes);
        }

        public static <T> Function<ArgCastBuilder<T, ?>, CastNode> asDouble() {
            return phaseBuilder -> CastDoubleBaseNodeGen.create(false, false, false);
        }

        public static <T> Function<ArgCastBuilder<T, ?>, CastNode> asDoubleVector() {
            return phaseBuilder -> CastDoubleNodeGen.create(false, false, false);
        }

        public static <T> Function<ArgCastBuilder<T, ?>, CastNode> asDoubleVector(boolean preserveNames, boolean preserveDimensions, boolean preserveAttributes) {
            return phaseBuilder -> CastDoubleNodeGen.create(preserveNames, preserveDimensions, preserveAttributes);
        }

        public static <T> Function<ArgCastBuilder<T, ?>, CastNode> asString() {
            return phaseBuilder -> CastStringBaseNodeGen.create(false, false, false);
        }

        public static <T> Function<ArgCastBuilder<T, ?>, CastNode> asStringVector() {
            return phaseBuilder -> CastStringNodeGen.create(false, false, false);
        }

        public static <T> Function<ArgCastBuilder<T, ?>, CastNode> asComplexVector() {
            return phaseBuilder -> CastComplexNodeGen.create(false, false, false);
        }

        public static <T> Function<ArgCastBuilder<T, ?>, CastNode> asRawVector() {
            return phaseBuilder -> CastRawNodeGen.create(false, false, false);
        }

        public static <T> Function<ArgCastBuilder<T, ?>, CastNode> asStringVector(boolean preserveNames, boolean preserveDimensions, boolean preserveAttributes) {
            return phaseBuilder -> CastStringNodeGen.create(preserveNames, preserveDimensions, preserveAttributes);
        }

        public static <T> Function<ArgCastBuilder<T, ?>, CastNode> asLogical() {
            return phaseBuilder -> CastLogicalBaseNodeGen.create(false, false, false);
        }

        public static <T> Function<ArgCastBuilder<T, ?>, CastNode> asLogicalVector() {
            return phaseBuilder -> CastLogicalNodeGen.create(false, false, false);
        }

        public static <T> Function<ArgCastBuilder<T, ?>, CastNode> asLogicalVector(boolean preserveNames, boolean preserveDimensions, boolean preserveAttributes) {
            return phaseBuilder -> CastLogicalNodeGen.create(preserveNames, preserveDimensions, preserveAttributes);
        }

        public static <T> Function<ArgCastBuilder<T, ?>, CastNode> asVector() {
            return phaseBuilder -> CastToVectorNodeGen.create(true);
        }

        public static <T> Function<ArgCastBuilder<T, ?>, CastNode> asVector(boolean preserveNonVector) {
            return phaseBuilder -> CastToVectorNodeGen.create(preserveNonVector);
        }

        public static <T> FindFirstNodeBuilder<T> findFirst(RBaseNode callObj, RError.Message message, Object... messageArgs) {
            return new FindFirstNodeBuilder<>(callObj, message, messageArgs);
        }

        public static <T> FindFirstNodeBuilder<T> findFirst(RError.Message message, Object... messageArgs) {
            return new FindFirstNodeBuilder<>(null, message, messageArgs);
        }

        public static <T> FindFirstNodeBuilder<T> findFirst() {
            return new FindFirstNodeBuilder<>(null, null, null);
        }

        public static <T> Function<ArgCastBuilder<T, ?>, CastNode> notNA(RBaseNode callObj, RError.Message message, Object... messageArgs) {
            return phaseBuilder -> NonNANodeGen.create(callObj, message, messageArgs, null);
        }

        public static <T> Function<ArgCastBuilder<T, ?>, CastNode> notNA(RError.Message message, Object... messageArgs) {
            return phaseBuilder -> NonNANodeGen.create(null, message, messageArgs, null);
        }

        public static <T> Function<ArgCastBuilder<T, ?>, CastNode> notNA(T naReplacement, RError.Message message, Object... messageArgs) {
            return phaseBuilder -> NonNANodeGen.create(null, message, messageArgs, naReplacement);
        }

        public static <T> Function<ArgCastBuilder<T, ?>, CastNode> notNA(T naReplacement) {
            return phaseBuilder -> NonNANodeGen.create(naReplacement);
        }

        public static <T> Function<ArgCastBuilder<T, ?>, CastNode> notNA() {
            return phaseBuilder -> NonNANodeGen.create(phaseBuilder.state().defaultError().callObj, phaseBuilder.state().defaultError().message, phaseBuilder.state().defaultError().args, null);
        }

        public static <T> Function<ArgCastBuilder<T, ?>, CastNode> asBoolean() {
            return map(toBoolean());
        }

        public static <T> ValuePredicateArgumentFilter<T> sameAs(T x) {
            return predefFilters().sameAs(x);
        }

        public static <T> ValuePredicateArgumentFilter<T> equalTo(T x) {
            return predefFilters().equalTo(x);
        }

        public static <T extends RAbstractVector> VectorPredicateArgumentFilter<T> notEmpty() {
            return predefFilters().notEmpty();
        }

        public static <T extends RAbstractVector> VectorPredicateArgumentFilter<T> singleElement() {
            return predefFilters().singleElement();
        }

        public static <T extends RAbstractVector, R extends T> VectorPredicateArgumentFilter<T> size(int s) {
            return predefFilters().size(s);
        }

        public static VectorPredicateArgumentFilter<RAbstractStringVector> elementAt(int index, String value) {
            return predefFilters().elementAt(index, value);
        }

        public static VectorPredicateArgumentFilter<RAbstractIntVector> elementAt(int index, int value) {
            return predefFilters().elementAt(index, value);
        }

        public static VectorPredicateArgumentFilter<RAbstractDoubleVector> elementAt(int index, double value) {
            return predefFilters().elementAt(index, value);
        }

        public static VectorPredicateArgumentFilter<RAbstractComplexVector> elementAt(int index, RComplex value) {
            return predefFilters().elementAt(index, value);
        }

        public static VectorPredicateArgumentFilter<RAbstractLogicalVector> elementAt(int index, byte value) {
            return predefFilters().elementAt(index, value);
        }

        public static <T extends RAbstractVector> VectorPredicateArgumentFilter<T> matrix() {
            return predefFilters().matrix();
        }

        public static <T extends RAbstractVector> VectorPredicateArgumentFilter<T> squareMatrix() {
            return predefFilters().squareMatrix();
        }

        public static <T extends RAbstractVector> VectorPredicateArgumentFilter<T> dimEq(int dim, int x) {
            return predefFilters().dimEq(dim, x);
        }

        public static <T extends RAbstractVector> VectorPredicateArgumentFilter<T> dimGt(int dim, int x) {
            return predefFilters().dimGt(dim, x);
        }

        public static ValuePredicateArgumentFilter<Boolean> trueValue() {
            return predefFilters().trueValue();
        }

        public static ValuePredicateArgumentFilter<Boolean> falseValue() {
            return predefFilters().falseValue();
        }

        public static ValuePredicateArgumentFilter<Byte> logicalTrue() {
            return predefFilters().logicalTrue();
        }

        public static ValuePredicateArgumentFilter<Byte> logicalFalse() {
            return predefFilters().logicalFalse();
        }

        public static ValuePredicateArgumentFilter<Integer> intNA() {
            return predefFilters().intNA();
        }

        public static ArgumentValueFilter<Integer> notIntNA() {
            return predefFilters().intNA().not();
        }

        public static ValuePredicateArgumentFilter<Byte> logicalNA() {
            return predefFilters().logicalNA();
        }

        public static ArgumentValueFilter<Byte> notLogicalNA() {
            return predefFilters().logicalNA().not();
        }

        public static ValuePredicateArgumentFilter<Double> doubleNA() {
            return predefFilters().doubleNA();
        }

        public static ArgumentValueFilter<Double> notDoubleNA() {
            return predefFilters().doubleNA().not();
        }

        public static ValuePredicateArgumentFilter<Double> isFractional() {
            return predefFilters().isFractional();
        }

        public static ValuePredicateArgumentFilter<Double> isFinite() {
            return predefFilters().isFinite();
        }

        public static ValuePredicateArgumentFilter<String> stringNA() {
            return predefFilters().stringNA();
        }

        public static ArgumentValueFilter<String> notStringNA() {
            return predefFilters().stringNA().not();
        }

        public static ValuePredicateArgumentFilter<Integer> eq(int x) {
            return predefFilters().eq(x);
        }

        public static ValuePredicateArgumentFilter<Double> eq(double x) {
            return predefFilters().eq(x);
        }

        public static ValuePredicateArgumentFilter<String> eq(String x) {
            return predefFilters().eq(x);
        }

        public static ArgumentValueFilter<Integer> neq(int x) {
            return predefFilters().eq(x).not();
        }

        public static ArgumentValueFilter<Double> neq(double x) {
            return predefFilters().eq(x).not();
        }

        public static ValuePredicateArgumentFilter<Integer> gt(int x) {
            return predefFilters().gt(x);
        }

        public static ValuePredicateArgumentFilter<Double> gt(double x) {
            return predefFilters().gt(x);
        }

        public static ValuePredicateArgumentFilter<Integer> gte(int x) {
            return predefFilters().gt(x - 1);
        }

        public static ValuePredicateArgumentFilter<Double> gte(double x) {
            return predefFilters().gte(x);
        }

        public static ValuePredicateArgumentFilter<Integer> lt(int x) {
            return predefFilters().lt(x);
        }

        public static ValuePredicateArgumentFilter<Double> lt(double x) {
            return predefFilters().lt(x);
        }

        public static ValuePredicateArgumentFilter<Integer> lte(int x) {
            return predefFilters().lt(x + 1);
        }

        public static ValuePredicateArgumentFilter<Double> lte(double x) {
            return predefFilters().lte(x);
        }

        public static ValuePredicateArgumentFilter<String> length(int l) {
            return predefFilters().length(l);
        }

        public static ValuePredicateArgumentFilter<String> isEmpty() {
            return predefFilters().lengthLt(1);
        }

        public static ValuePredicateArgumentFilter<String> lengthGt(int l) {
            return predefFilters().lengthGt(l);
        }

        public static ValuePredicateArgumentFilter<String> lengthGte(int l) {
            return predefFilters().lengthGt(l - 1);
        }

        public static ValuePredicateArgumentFilter<String> lengthLt(int l) {
            return predefFilters().lengthLt(l);
        }

        public static ValuePredicateArgumentFilter<String> lengthLte(int l) {
            return predefFilters().lengthLt(l + 1);
        }

        public static ValuePredicateArgumentFilter<Integer> gt0() {
            return predefFilters().gt(0);
        }

        public static ValuePredicateArgumentFilter<Integer> gte0() {
            return predefFilters().gt(-1);
        }

        public static ValuePredicateArgumentFilter<Integer> gt1() {
            return predefFilters().gt(1);
        }

        public static ValuePredicateArgumentFilter<Integer> gte1() {
            return predefFilters().gt(0);
        }

        public static <R> TypePredicateArgumentFilter<Object, R> instanceOf(Class<R> cls) {
            return predefFilters().instanceOf(cls);
        }

        public static <R extends RAbstractIntVector> TypePredicateArgumentFilter<Object, R> integerValue() {
            return predefFilters().integerValue();
        }

        public static <R extends RAbstractStringVector> TypePredicateArgumentFilter<Object, R> stringValue() {
            return predefFilters().stringValue();
        }

        public static <R extends RAbstractDoubleVector> TypePredicateArgumentFilter<Object, R> doubleValue() {
            return predefFilters().doubleValue();
        }

        public static <R extends RAbstractLogicalVector> TypePredicateArgumentFilter<Object, R> logicalValue() {
            return predefFilters().logicalValue();
        }

        public static <R extends RAbstractComplexVector> TypePredicateArgumentFilter<Object, R> complexValue() {
            return predefFilters().complexValue();
        }

        public static <R extends RAbstractRawVector> TypePredicateArgumentFilter<Object, R> rawValue() {
            return predefFilters().rawValue();
        }

        public static <R> TypePredicateArgumentFilter<Object, R> anyValue() {
            return predefFilters().anyValue();
        }

        @SuppressWarnings({"rawtypes", "unchecked", "cast"})
        public static ArgumentTypeFilter<Object, RAbstractVector> numericValue() {
            ArgumentTypeFilter f = integerValue().or(doubleValue()).or(logicalValue());
            return (ArgumentTypeFilter<Object, RAbstractVector>) f;
        }

        /**
         * Checks that the argument is a list or vector/scalar of type numeric, string, complex or
         * raw.
         */
        @SuppressWarnings({"rawtypes", "unchecked", "cast"})
        public static ArgumentTypeFilter<Object, RAbstractVector> abstractVectorValue() {
            ArgumentTypeFilter f = numericValue().or(stringValue()).or(complexValue()).or(rawValue()).or(instanceOf(RAbstractListVector.class));
            return (ArgumentTypeFilter<Object, RAbstractVector>) f;
        }

        /**
         * @deprecated tests for scalar types are dangerous
         */
        @Deprecated
        public static TypePredicateArgumentFilter<Object, String> scalarStringValue() {
            return predefFilters().scalarStringValue();
        }

        /**
         * @deprecated tests for scalar types are dangerous
         */
        @Deprecated
        public static TypePredicateArgumentFilter<Object, Integer> scalarIntegerValue() {
            return predefFilters().scalarIntegerValue();
        }

        /**
         * @deprecated tests for scalar types are dangerous
         */
        @Deprecated
        public static TypePredicateArgumentFilter<Object, Double> scalarDoubleValue() {
            return predefFilters().scalarDoubleValue();
        }

        /**
         * @deprecated tests for scalar types are dangerous
         */
        @Deprecated
        public static TypePredicateArgumentFilter<Object, Byte> scalarLogicalValue() {
            return predefFilters().scalarLogicalValue();
        }

        /**
         * @deprecated tests for scalar types are dangerous
         */
        @Deprecated
        public static TypePredicateArgumentFilter<Object, RComplex> scalarComplexValue() {
            return predefFilters().scalarComplexValue();
        }

        public static ValuePredicateArgumentMapper<Byte, Boolean> toBoolean() {
            return predefMappers().toBoolean();
        }

        public static ValuePredicateArgumentMapper<Double, Integer> doubleToInt() {
            return predefMappers().doubleToInt();
        }

        public static ValuePredicateArgumentMapper<String, Integer> charAt0(int defaultValue) {
            return predefMappers().charAt0(defaultValue);
        }

        public static <T> ValuePredicateArgumentMapper<T, RNull> nullConstant() {
            return predefMappers().nullConstant();
        }

        public static <T> ValuePredicateArgumentMapper<T, RMissing> missingConstant() {
            return predefMappers().missingConstant();
        }

        public static <T> ValuePredicateArgumentMapper<T, String> constant(String s) {
            return predefMappers().constant(s);
        }

        public static <T> ValuePredicateArgumentMapper<T, Integer> constant(int i) {
            return predefMappers().constant(i);
        }

        public static <T> ValuePredicateArgumentMapper<T, Double> constant(double d) {
            return predefMappers().constant(d);
        }

        public static <T> ValuePredicateArgumentMapper<T, Byte> constant(byte l) {
            return predefMappers().constant(l);
        }

        public static <T> ValuePredicateArgumentMapper<T, RIntVector> emptyIntegerVector() {
            return predefMappers.emptyIntegerVector();
        }

        public static <T> ValuePredicateArgumentMapper<T, RDoubleVector> emptyDoubleVector() {
            return predefMappers.emptyDoubleVector();
        }

        public static <T> ValuePredicateArgumentMapper<T, RLogicalVector> emptyLogicalVector() {
            return predefMappers.emptyLogicalVector();
        }

        public static <T> ValuePredicateArgumentMapper<T, RComplexVector> emptyComplexVector() {
            return predefMappers.emptyComplexVector();
        }

        public static <T> ValuePredicateArgumentMapper<T, RStringVector> emptyStringVector() {
            return predefMappers.emptyStringVector();
        }

        public static <T> ValuePredicateArgumentMapper<T, RList> emptyList() {
            return predefMappers.emptyList();
        }

        /**
         * @deprecated use the <code>mapNull</code> step, e.g.
         *             <code>casts.arg("x").mapNull(emptyDoubleVector())</code> or
         *             <code>casts.arg("x").conf(c -> c.mapNull(emptyDoubleVector()))</code> instead
         */
        @Deprecated
        public static <T> ArgumentMapper<T, T> defaultValue(T defVal) {
            return predefMappers().defaultValue(defVal);
        }

    }

    @SuppressWarnings("unchecked")
    public interface ArgCastBuilder<T, THIS> {

        ArgCastBuilderState state();

        default CastBuilder builder() {
            return state().castBuilder();
        }

        default THIS defaultError(RBaseNode callObj, RError.Message message, Object... args) {
            state().setDefaultError(callObj, message, args);
            return (THIS) this;
        }

        default THIS defaultError(RError.Message message, Object... args) {
            state().setDefaultError(message, args);
            return (THIS) this;
        }

        default THIS defaultWarning(RBaseNode callObj, RError.Message message, Object... args) {
            state().setDefaultWarning(callObj, message, args);
            return (THIS) this;
        }

        default THIS defaultWarning(RError.Message message, Object... args) {
            state().setDefaultWarning(message, args);
            return (THIS) this;
        }

        default THIS shouldBe(ArgumentFilter<? super T, ?> argFilter, RError.Message message, Object... messageArgs) {
            state().castBuilder().insert(state().index(), () -> FilterNode.create(argFilter, true, null, message, messageArgs, state().boxPrimitives));
            return (THIS) this;
        }

        default THIS shouldBe(ArgumentFilter<? super T, ?> argFilter, RBaseNode callObj, RError.Message message, Object... messageArgs) {
            state().castBuilder().insert(state().index(), () -> FilterNode.create(argFilter, true, callObj, message, messageArgs, state().boxPrimitives));
            return (THIS) this;
        }

        default THIS shouldBe(ArgumentFilter<? super T, ?> argFilter) {
            return shouldBe(argFilter, state().defaultWarning().callObj, state().defaultWarning().message, state().defaultWarning().args);
        }

        default <R, THAT extends ArgCastBuilder<R, THAT>> THAT alias(Function<THIS, THAT> aliaser) {
            return aliaser.apply((THIS) this);
        }

    }

    interface ArgCastBuilderFactory {

        PreinitialPhaseBuilder<Object> newPreinitialPhaseBuilder();

        <T> InitialPhaseBuilder<T> newInitialPhaseBuilder(ArgCastBuilder<?, ?> currentBuilder);

        <T extends RAbstractVector, S> CoercedPhaseBuilder<T, S> newCoercedPhaseBuilder(ArgCastBuilder<?, ?> currentBuilder, Class<?> elementClass);

        <T> HeadPhaseBuilder<T> newHeadPhaseBuilder(ArgCastBuilder<?, ?> currentBuilder);

    }

    public static class DefaultError {
        public final RBaseNode callObj;
        public final RError.Message message;
        public final Object[] args;

        DefaultError(RBaseNode callObj, RError.Message message, Object... args) {
            this.callObj = callObj;
            this.message = message;
            this.args = args;
        }

        public DefaultError fixCallObj(RBaseNode callObjFix) {
            if (callObj == null) {
                return new DefaultError(callObjFix, message, args);
            } else {
                return this;
            }
        }

    }

    static class ArgCastBuilderState {
        private final DefaultError defaultDefaultError;

        private final int argumentIndex;
        private final String argumentName;
        final ArgCastBuilderFactory factory;
        private final CastBuilder cb;
        final boolean boxPrimitives;
        private DefaultError defError;
        private DefaultError defWarning;

        ArgCastBuilderState(int argumentIndex, String argumentName, ArgCastBuilderFactory fact, CastBuilder cb, boolean boxPrimitives) {
            this.argumentIndex = argumentIndex;
            this.argumentName = argumentName;
            this.factory = fact;
            this.cb = cb;
            this.boxPrimitives = boxPrimitives;
            this.defaultDefaultError = new DefaultError(null, RError.Message.INVALID_ARGUMENT, argumentName);
        }

        ArgCastBuilderState(ArgCastBuilderState prevState, boolean boxPrimitives) {
            this.argumentIndex = prevState.argumentIndex;
            this.argumentName = prevState.argumentName;
            this.factory = prevState.factory;
            this.cb = prevState.cb;
            this.boxPrimitives = boxPrimitives;
            this.defError = prevState.defError;
            this.defWarning = prevState.defWarning;
            this.defaultDefaultError = new DefaultError(null, RError.Message.INVALID_ARGUMENT, argumentName);
        }

        public int index() {
            return argumentIndex;
        }

        public String name() {
            return argumentName;
        }

        public CastBuilder castBuilder() {
            return cb;
        }

        boolean isDefaultErrorDefined() {
            return defError != null;
        }

        boolean isDefaultWarningDefined() {
            return defWarning != null;
        }

        void setDefaultError(RBaseNode callObj, RError.Message message, Object... args) {
            defError = new DefaultError(callObj, message, args);
        }

        void setDefaultError(RError.Message message, Object... args) {
            defError = new DefaultError(null, message, args);
        }

        void setDefaultWarning(RBaseNode callObj, RError.Message message, Object... args) {
            defWarning = new DefaultError(callObj, message, args);
        }

        void setDefaultWarning(RError.Message message, Object... args) {
            defWarning = new DefaultError(null, message, args);
        }

        DefaultError defaultError() {
            return defError == null ? defaultDefaultError : defError;
        }

        DefaultError defaultError(RBaseNode callObj, RError.Message defaultDefaultMessage, Object... defaultDefaultArgs) {
            return defError == null ? new DefaultError(callObj, defaultDefaultMessage, defaultDefaultArgs) : defError;
        }

        DefaultError defaultError(RError.Message defaultDefaultMessage, Object... defaultDefaultArgs) {
            return defError == null ? new DefaultError(null, defaultDefaultMessage, defaultDefaultArgs) : defError;
        }

        DefaultError defaultWarning() {
            return defWarning == null ? defaultDefaultError : defWarning;
        }

        DefaultError defaultWarning(RBaseNode callObj, RError.Message defaultDefaultMessage, Object... defaultDefaultArgs) {
            return defWarning == null ? new DefaultError(callObj, defaultDefaultMessage, defaultDefaultArgs) : defWarning;
        }

        DefaultError defaultWarning(RError.Message defaultDefaultMessage, Object... defaultDefaultArgs) {
            return defWarning == null ? new DefaultError(null, defaultDefaultMessage, defaultDefaultArgs) : defWarning;
        }

        void mustBe(ArgumentFilter<?, ?> argFilter, RBaseNode callObj, RError.Message message, Object... messageArgs) {
            castBuilder().insert(index(), () -> FilterNode.create(argFilter, false, callObj, message, messageArgs, boxPrimitives));
        }

        void mustBe(ArgumentFilter<?, ?> argFilter) {
            mustBe(argFilter, defaultError().callObj, defaultError().message, defaultError().args);
        }

        void shouldBe(ArgumentFilter<?, ?> argFilter, RBaseNode callObj, RError.Message message, Object... messageArgs) {
            castBuilder().insert(index(), () -> FilterNode.create(argFilter, true, callObj, message, messageArgs, boxPrimitives));
        }

        void shouldBe(ArgumentFilter<?, ?> argFilter) {
            shouldBe(argFilter, defaultWarning().callObj, defaultWarning().message, defaultWarning().args);
        }

    }

    abstract class ArgCastBuilderBase<T, THIS> implements ArgCastBuilder<T, THIS> {

        private final ArgCastBuilderState st;

        ArgCastBuilderBase(ArgCastBuilderState state) {
            this.st = state;
        }

        @Override
        public ArgCastBuilderState state() {
            return st;
        }
    }

    public interface InitialPhaseBuilder<T> extends ArgCastBuilder<T, InitialPhaseBuilder<T>> {

        default <S> InitialPhaseBuilder<S> mustBe(ArgumentFilter<? super T, S> argFilter, RBaseNode callObj, RError.Message message, Object... messageArgs) {
            state().mustBe(argFilter, callObj, message, messageArgs);
            return state().factory.newInitialPhaseBuilder(this);
        }

        default <S> InitialPhaseBuilder<S> mustBe(ArgumentFilter<? super T, S> argFilter, RError.Message message, Object... messageArgs) {
            state().mustBe(argFilter, null, message, messageArgs);
            return state().factory.newInitialPhaseBuilder(this);
        }

        default <S> InitialPhaseBuilder<S> mustBe(ArgumentFilter<? super T, S> argFilter) {
            return mustBe(argFilter, state().defaultError().callObj, state().defaultError().message, state().defaultError().args);
        }

        default <S> InitialPhaseBuilder<S> mustBe(Class<S> cls, RBaseNode callObj, RError.Message message, Object... messageArgs) {
            mustBe(Predef.instanceOf(cls), callObj, message, messageArgs);
            return state().factory.newInitialPhaseBuilder(this);
        }

        default <S> InitialPhaseBuilder<S> mustBe(Class<S> cls, RError.Message message, Object... messageArgs) {
            mustBe(Predef.instanceOf(cls), message, messageArgs);
            return state().factory.newInitialPhaseBuilder(this);
        }

        default <S> InitialPhaseBuilder<S> mustBe(Class<S> cls) {
            mustBe(Predef.instanceOf(cls));
            return state().factory.newInitialPhaseBuilder(this);
        }

        default <S> InitialPhaseBuilder<S> shouldBe(Class<S> cls, RBaseNode callObj, RError.Message message, Object... messageArgs) {
            shouldBe(Predef.instanceOf(cls), callObj, message, messageArgs);
            return state().factory.newInitialPhaseBuilder(this);
        }

        default <S> InitialPhaseBuilder<S> shouldBe(Class<S> cls, RError.Message message, Object... messageArgs) {
            shouldBe(Predef.instanceOf(cls), message, messageArgs);
            return state().factory.newInitialPhaseBuilder(this);
        }

        default <S> InitialPhaseBuilder<S> shouldBe(Class<S> cls) {
            shouldBe(Predef.instanceOf(cls));
            return state().factory.newInitialPhaseBuilder(this);
        }

        default <S> InitialPhaseBuilder<S> map(ArgumentMapper<T, S> mapFn) {
            state().castBuilder().insert(state().index(), () -> MapNode.create(mapFn));
            return state().factory.newInitialPhaseBuilder(this);
        }

        @SuppressWarnings("overloads")
        default <S, R> InitialPhaseBuilder<Object> mapIf(ArgumentFilter<? super T, S> argFilter, ArgumentMapper<S, R> trueBranchMapper) {
            state().castBuilder().insert(state().index(), () -> ConditionalMapNode.create(argFilter, MapNode.create(trueBranchMapper), null));

            return state().factory.newInitialPhaseBuilder(this);
        }

        default <S, R> InitialPhaseBuilder<Object> mapIf(ArgumentFilter<? super T, S> argFilter, CastNodeFactory trueBranchNodeFact) {
            state().castBuilder().insert(state().index(), () -> ConditionalMapNode.create(argFilter, trueBranchNodeFact.create(), null));

            return state().factory.newInitialPhaseBuilder(this);
        }

        @SuppressWarnings("overloads")
        default <S, R> InitialPhaseBuilder<Object> mapIf(ArgumentFilter<? super T, S> argFilter, ArgumentMapper<S, R> trueBranchMapper, ArgumentMapper<T, T> falseBranchMapper) {
            state().castBuilder().insert(
                            state().index(),
                            () -> ConditionalMapNode.create(argFilter, MapNode.create(trueBranchMapper),
                                            MapNode.create(falseBranchMapper)));

            return state().factory.newInitialPhaseBuilder(this);
        }

        default <S, R> InitialPhaseBuilder<Object> mapIf(ArgumentFilter<? super T, S> argFilter, CastNodeFactory trueBranchNodeFact, CastNodeFactory falseBranchNodeFact) {
            state().castBuilder().insert(state().index(), () -> ConditionalMapNode.create(argFilter, trueBranchNodeFact.create(), falseBranchNodeFact.create()));

            return state().factory.newInitialPhaseBuilder(this);
        }

        @SuppressWarnings("overloads")
        default <S, R> InitialPhaseBuilder<Object> mapIf(ArgumentFilter<? super T, S> argFilter, Function<ArgCastBuilder<T, ?>, CastNode> trueBranchNodeFactory) {
            state().castBuilder().insert(state().index(), () -> ConditionalMapNode.create(argFilter, trueBranchNodeFactory.apply(this), null));

            return state().factory.newInitialPhaseBuilder(this);
        }

        @SuppressWarnings("overloads")
        default <S, R> InitialPhaseBuilder<Object> mapIf(ArgumentFilter<? super T, S> argFilter, Function<ArgCastBuilder<T, ?>, CastNode> trueBranchNodeFactory,
                        Function<ArgCastBuilder<T, ?>, CastNode> falseBranchNodeFactory) {
            state().castBuilder().insert(state().index(), () -> ConditionalMapNode.create(argFilter, trueBranchNodeFactory.apply(this), falseBranchNodeFactory.apply(this)));

            return state().factory.newInitialPhaseBuilder(this);
        }

        default InitialPhaseBuilder<T> notNA(RBaseNode callObj, RError.Message message, Object... messageArgs) {
            state().castBuilder().insert(state().index(), () -> NonNANodeGen.create(callObj, message, messageArgs, null));
            return this;
        }

        default InitialPhaseBuilder<T> notNA(RError.Message message, Object... messageArgs) {
            state().castBuilder().insert(state().index(), () -> NonNANodeGen.create(null, message, messageArgs, null));
            return this;
        }

        default InitialPhaseBuilder<T> notNA(T naReplacement, RBaseNode callObj, RError.Message message, Object... messageArgs) {
            state().castBuilder().insert(state().index(), () -> NonNANodeGen.create(callObj, message, messageArgs, naReplacement));
            return this;
        }

        default InitialPhaseBuilder<T> notNA(T naReplacement, RError.Message message, Object... messageArgs) {
            state().castBuilder().insert(state().index(), () -> NonNANodeGen.create(null, message, messageArgs, naReplacement));
            return this;
        }

        default InitialPhaseBuilder<T> notNA(T naReplacement) {
            state().castBuilder().insert(state().index(), () -> NonNANodeGen.create(naReplacement));
            return this;
        }

        /**
         * This method should be used as a step in pipeline, not as an argument to {@code mustBe}.
         * Example: {@code casts.arg("x").notNA()}.
         */
        default InitialPhaseBuilder<T> notNA() {
            state().castBuilder().insert(state().index(), () -> NonNANodeGen.create(state().defaultError().callObj, state().defaultError().message, state().defaultError().args, null));
            return this;
        }

        default CoercedPhaseBuilder<RAbstractIntVector, Integer> asIntegerVector(boolean preserveNames, boolean dimensionsPreservation, boolean attrPreservation) {
            state().castBuilder().toInteger(state().index(), preserveNames, dimensionsPreservation, attrPreservation);
            return state().factory.newCoercedPhaseBuilder(this, Integer.class);
        }

        default CoercedPhaseBuilder<RAbstractIntVector, Integer> asIntegerVector() {
            return asIntegerVector(false, false, false);
        }

        default CoercedPhaseBuilder<RAbstractDoubleVector, Double> asDoubleVector(boolean preserveNames, boolean dimensionsPreservation, boolean attrPreservation) {
            state().castBuilder().toDouble(state().index(), preserveNames, dimensionsPreservation, attrPreservation);
            return state().factory.newCoercedPhaseBuilder(this, Double.class);
        }

        default CoercedPhaseBuilder<RAbstractDoubleVector, Double> asDoubleVector() {
            return asDoubleVector(false, false, false);
        }

        default CoercedPhaseBuilder<RAbstractDoubleVector, Byte> asLogicalVector(boolean preserveNames, boolean dimensionsPreservation, boolean attrPreservation) {
            state().castBuilder().insert(state().index(), () -> CastLogicalNodeGen.create(preserveNames, dimensionsPreservation, attrPreservation));
            return state().factory.newCoercedPhaseBuilder(this, Byte.class);
        }

        default CoercedPhaseBuilder<RAbstractDoubleVector, Byte> asLogicalVector() {
            return asLogicalVector(false, false, false);
        }

        default CoercedPhaseBuilder<RAbstractStringVector, String> asStringVector(boolean preserveNames, boolean dimensionsPreservation, boolean attrPreservation) {
            state().castBuilder().toCharacter(state().index(), preserveNames, dimensionsPreservation, attrPreservation);
            return state().factory.newCoercedPhaseBuilder(this, String.class);
        }

        default CoercedPhaseBuilder<RAbstractStringVector, String> asStringVector() {
            state().castBuilder().toCharacter(state().index());
            return state().factory.newCoercedPhaseBuilder(this, String.class);
        }

        default CoercedPhaseBuilder<RAbstractComplexVector, RComplex> asComplexVector() {
            state().castBuilder().toComplex(state().index());
            return state().factory.newCoercedPhaseBuilder(this, RComplex.class);
        }

        default CoercedPhaseBuilder<RAbstractRawVector, RRaw> asRawVector() {
            state().castBuilder().toRaw(state().index());
            return state().factory.newCoercedPhaseBuilder(this, RRaw.class);
        }

        default CoercedPhaseBuilder<RAbstractVector, Object> asVector() {
            state().castBuilder().toVector(state().index());
            return state().factory.newCoercedPhaseBuilder(this, Object.class);
        }

        default CoercedPhaseBuilder<RAbstractVector, Object> asVector(boolean preserveNonVector) {
            state().castBuilder().toVector(state().index(), preserveNonVector);
            return state().factory.newCoercedPhaseBuilder(this, Object.class);
        }

        default HeadPhaseBuilder<RAttributable> asAttributable(boolean preserveNames, boolean dimensionsPreservation, boolean attrPreservation) {
            state().castBuilder().toAttributable(state().index(), preserveNames, dimensionsPreservation, attrPreservation);
            return state().factory.newHeadPhaseBuilder(this);
        }

    }

    public interface PreinitialPhaseBuilder<T> extends InitialPhaseBuilder<T> {

        default InitialPhaseBuilder<T> conf(Function<PipelineConfigBuilder, PipelineConfigBuilder> cfgLambda) {
            cfgLambda.apply(getPipelineConfigBuilder());
            return this;
        }

        default InitialPhaseBuilder<T> allowNull() {
            return conf(c -> c.allowNull());
        }

        default InitialPhaseBuilder<T> mustNotBeNull() {
            return conf(c -> c.mustNotBeNull(state().defaultError().callObj, state().defaultError().message, state().defaultError().args));
        }

        default InitialPhaseBuilder<T> mustNotBeNull(RError.Message errorMsg, Object... msgArgs) {
            return conf(c -> c.mustNotBeNull(null, errorMsg, msgArgs));
        }

        default InitialPhaseBuilder<T> mustNotBeNull(RBaseNode callObj, RError.Message errorMsg, Object... msgArgs) {
            return conf(c -> c.mustNotBeNull(callObj, errorMsg, msgArgs));
        }

        default InitialPhaseBuilder<T> mapNull(ArgumentMapper<? super RNull, ?> mapper) {
            return conf(c -> c.mapNull(mapper));
        }

        default InitialPhaseBuilder<T> allowMissing() {
            return conf(c -> c.allowMissing());
        }

        default InitialPhaseBuilder<T> mustNotBeMissing() {
            return conf(c -> c.mustNotBeMissing(state().defaultError().callObj, state().defaultError().message, state().defaultError().args));
        }

        default InitialPhaseBuilder<T> mustNotBeMissing(RError.Message errorMsg, Object... msgArgs) {
            return conf(c -> c.mustNotBeMissing(null, errorMsg, msgArgs));
        }

        default InitialPhaseBuilder<T> mustNotBeMissing(RBaseNode callObj, RError.Message errorMsg, Object... msgArgs) {
            return conf(c -> c.mustNotBeMissing(callObj, errorMsg, msgArgs));
        }

        default InitialPhaseBuilder<T> mapMissing(ArgumentMapper<? super RMissing, ?> mapper) {
            return conf(c -> c.mapMissing(mapper));
        }

        default InitialPhaseBuilder<T> allowNullAndMissing() {
            return conf(c -> c.allowMissing().allowNull());
        }

        @Override
        default PreinitialPhaseBuilder<T> defaultError(RBaseNode callObj, RError.Message message, Object... args) {
            state().setDefaultError(callObj, message, args);
            getPipelineConfigBuilder().updateDefaultError();
            return this;
        }

        @Override
        default PreinitialPhaseBuilder<T> defaultError(RError.Message message, Object... args) {
            return defaultError(null, message, args);
        }

        @Override
        default PreinitialPhaseBuilder<T> defaultWarning(RBaseNode callObj, RError.Message message, Object... args) {
            state().setDefaultWarning(callObj, message, args);
            return this;
        }

        @Override
        default PreinitialPhaseBuilder<T> defaultWarning(RError.Message message, Object... args) {
            return defaultWarning(null, message, args);
        }

        PipelineConfigBuilder getPipelineConfigBuilder();
    }

    public interface CoercedPhaseBuilder<T extends RAbstractVector, S> extends ArgCastBuilder<T, CoercedPhaseBuilder<T, S>> {

        /**
         * The inserted cast node returns the default value if the input vector is empty. It also
         * reports the warning message.
         */
        default HeadPhaseBuilder<S> findFirst(S defaultValue, RError.Message message, Object... messageArgs) {
            state().castBuilder().insert(state().index(), () -> FindFirstNodeGen.create(elementClass(), null, message, messageArgs, defaultValue));
            return state().factory.newHeadPhaseBuilder(this);
        }

        default HeadPhaseBuilder<S> findFirst(S defaultValue, RBaseNode callObj, RError.Message message, Object... messageArgs) {
            state().castBuilder().insert(state().index(), () -> FindFirstNodeGen.create(elementClass(), callObj, message, messageArgs, defaultValue));
            return state().factory.newHeadPhaseBuilder(this);
        }

        /**
         * The inserted cast node raises an error if the input vector is empty.
         */
        default HeadPhaseBuilder<S> findFirst(RError.Message message, Object... messageArgs) {
            state().castBuilder().insert(state().index(), () -> FindFirstNodeGen.create(elementClass(), null, message, messageArgs, null));
            return state().factory.newHeadPhaseBuilder(this);
        }

        default HeadPhaseBuilder<S> findFirst(RBaseNode callObj, RError.Message message, Object... messageArgs) {
            state().castBuilder().insert(state().index(), () -> FindFirstNodeGen.create(elementClass(), callObj, message, messageArgs, null));
            return state().factory.newHeadPhaseBuilder(this);
        }

        /**
         * The inserted cast node raises the default error, if defined, or
         * RError.Message.LENGTH_ZERO error if the input vector is empty.
         */
        default HeadPhaseBuilder<S> findFirst() {
            DefaultError err = state().isDefaultErrorDefined() ? state().defaultError() : new DefaultError(null, RError.Message.LENGTH_ZERO);
            state().castBuilder().insert(state().index(),
                            () -> FindFirstNodeGen.create(elementClass(), err.callObj, err.message, err.args, null));
            return state().factory.newHeadPhaseBuilder(this);
        }

        /**
         * The inserted cast node returns the default value if the input vector is empty. It reports
         * no warning message.
         */
        default HeadPhaseBuilder<S> findFirst(S defaultValue) {
            assert defaultValue != null : "defaultValue cannot be null";
            state().castBuilder().insert(state().index(), () -> FindFirstNodeGen.create(elementClass(), defaultValue));
            return state().factory.newHeadPhaseBuilder(this);
        }

        Class<?> elementClass();

        default CoercedPhaseBuilder<T, S> mustBe(ArgumentFilter<? super T, ? extends T> argFilter, RBaseNode callObj, RError.Message message, Object... messageArgs) {
            state().mustBe(argFilter, callObj, message, messageArgs);
            return this;
        }

        default CoercedPhaseBuilder<T, S> mustBe(ArgumentFilter<? super T, ? extends T> argFilter, RError.Message message, Object... messageArgs) {
            state().mustBe(argFilter, null, message, messageArgs);
            return this;
        }

        default CoercedPhaseBuilder<T, S> mustBe(ArgumentFilter<? super T, ? extends T> argFilter) {
            return mustBe(argFilter, state().defaultError().callObj, state().defaultError().message, state().defaultError().args);
        }

    }

    public interface HeadPhaseBuilder<T> extends ArgCastBuilder<T, HeadPhaseBuilder<T>> {

        default <S> HeadPhaseBuilder<S> map(ArgumentMapper<T, S> mapFn) {
            state().castBuilder().insert(state().index(), () -> MapNode.create(mapFn));
            return state().factory.newHeadPhaseBuilder(this);
        }

        @SuppressWarnings("overloads")
        default <S, R> HeadPhaseBuilder<Object> mapIf(ArgumentFilter<? super T, S> argFilter, ArgumentMapper<S, R> trueBranchMapper) {
            state().castBuilder().insert(state().index(), () -> ConditionalMapNode.create(argFilter, MapNode.create(trueBranchMapper), null));

            return state().factory.newHeadPhaseBuilder(this);
        }

        default <S, R> HeadPhaseBuilder<Object> mapIf(ArgumentFilter<? super T, S> argFilter, CastNodeFactory trueBranchNodeFact) {
            state().castBuilder().insert(state().index(), () -> ConditionalMapNode.create(argFilter, trueBranchNodeFact.create(), null));

            return state().factory.newHeadPhaseBuilder(this);
        }

        @SuppressWarnings("overloads")
        default <S, R> HeadPhaseBuilder<Object> mapIf(ArgumentFilter<? super T, S> argFilter, ArgumentMapper<S, R> trueBranchMapper, ArgumentMapper<T, T> falseBranchMapper) {
            state().castBuilder().insert(
                            state().index(),
                            () -> ConditionalMapNode.create(argFilter, MapNode.create(trueBranchMapper),
                                            MapNode.create(falseBranchMapper)));

            return state().factory.newHeadPhaseBuilder(this);
        }

        @SuppressWarnings("overloads")
        default <S, R> HeadPhaseBuilder<Object> mapIf(ArgumentFilter<? super T, S> argFilter, Function<ArgCastBuilder<T, ?>, CastNode> trueBranchNodeFactory) {
            state().castBuilder().insert(state().index(), () -> ConditionalMapNode.create(argFilter, trueBranchNodeFactory.apply(this), null));

            return state().factory.newHeadPhaseBuilder(this);
        }

        @SuppressWarnings("overloads")
        default <S, R> HeadPhaseBuilder<Object> mapIf(ArgumentFilter<? super T, S> argFilter, Function<ArgCastBuilder<T, ?>, CastNode> trueBranchNodeFactory,
                        Function<ArgCastBuilder<T, ?>, CastNode> falseBranchNodeFactory) {
            state().castBuilder().insert(state().index(), () -> ConditionalMapNode.create(argFilter, trueBranchNodeFactory.apply(this), falseBranchNodeFactory.apply(this)));

            return state().factory.newHeadPhaseBuilder(this);
        }

        default <S, R> HeadPhaseBuilder<Object> mapIf(ArgumentFilter<? super T, S> argFilter, CastNodeFactory trueBranchNodeFact, CastNodeFactory falseBranchNodeFact) {
            state().castBuilder().insert(state().index(), () -> ConditionalMapNode.create(argFilter, trueBranchNodeFact.create(), falseBranchNodeFact.create()));

            return state().factory.newHeadPhaseBuilder(this);
        }

        default <S> HeadPhaseBuilder<S> mustBe(ArgumentFilter<? super T, S> argFilter, RBaseNode callObj, RError.Message message, Object... messageArgs) {
            state().mustBe(argFilter, callObj, message, messageArgs);
            return state().factory.newHeadPhaseBuilder(this);
        }

        default <S> HeadPhaseBuilder<S> mustBe(ArgumentFilter<? super T, S> argFilter, RError.Message message, Object... messageArgs) {
            state().mustBe(argFilter, null, message, messageArgs);
            return state().factory.newHeadPhaseBuilder(this);
        }

        default <S> HeadPhaseBuilder<S> mustBe(ArgumentFilter<? super T, S> argFilter) {
            return mustBe(argFilter, state().defaultError().callObj, state().defaultError().message, state().defaultError().args);
        }

        default <S> HeadPhaseBuilder<S> mustBe(Class<S> cls, RError.Message message, Object... messageArgs) {
            mustBe(Predef.instanceOf(cls), message, messageArgs);
            return state().factory.newHeadPhaseBuilder(this);
        }

        default <S> HeadPhaseBuilder<S> mustBe(Class<S> cls) {
            mustBe(Predef.instanceOf(cls));
            return state().factory.newHeadPhaseBuilder(this);
        }

        default <S> HeadPhaseBuilder<S> shouldBe(Class<S> cls, RBaseNode callObj, RError.Message message, Object... messageArgs) {
            shouldBe(Predef.instanceOf(cls), callObj, message, messageArgs);
            return state().factory.newHeadPhaseBuilder(this);
        }

        default <S> HeadPhaseBuilder<S> shouldBe(Class<S> cls, RError.Message message, Object... messageArgs) {
            shouldBe(Predef.instanceOf(cls), message, messageArgs);
            return state().factory.newHeadPhaseBuilder(this);
        }

        default <S> HeadPhaseBuilder<S> shouldBe(Class<S> cls) {
            shouldBe(Predef.instanceOf(cls));
            return state().factory.newHeadPhaseBuilder(this);
        }

        default HeadPhaseBuilder<T> notNA(RBaseNode callObj, RError.Message message, Object... messageArgs) {
            state().castBuilder().insert(state().index(), () -> NonNANodeGen.create(callObj, message, messageArgs, null));
            return this;
        }

        default HeadPhaseBuilder<T> notNA(RError.Message message, Object... messageArgs) {
            state().castBuilder().insert(state().index(), () -> NonNANodeGen.create(null, message, messageArgs, null));
            return this;
        }

        default HeadPhaseBuilder<T> notNA(T naReplacement, RBaseNode callObj, RError.Message message, Object... messageArgs) {
            state().castBuilder().insert(state().index(), () -> NonNANodeGen.create(callObj, message, messageArgs, naReplacement));
            return this;
        }

        default HeadPhaseBuilder<T> notNA(T naReplacement, RError.Message message, Object... messageArgs) {
            state().castBuilder().insert(state().index(), () -> NonNANodeGen.create(null, message, messageArgs, naReplacement));
            return this;
        }

        default HeadPhaseBuilder<T> notNA() {
            state().castBuilder().insert(state().index(), () -> NonNANodeGen.create(state().defaultError().callObj, state().defaultError().message, state().defaultError().args, null));
            return this;
        }

        default HeadPhaseBuilder<T> notNA(T naReplacement) {
            state().castBuilder().insert(state().index(), () -> NonNANodeGen.create(naReplacement));
            return this;
        }

    }

    final class ArgCastBuilderFactoryImpl implements ArgCastBuilderFactory {

        private final int argumentIndex;
        private final String argumentName;

        ArgCastBuilderFactoryImpl(int argumentIndex, String argumentName) {
            this.argumentIndex = argumentIndex;
            this.argumentName = argumentName;
        }

        @Override
        public PreinitialPhaseBuilderImpl<Object> newPreinitialPhaseBuilder() {
            return new PreinitialPhaseBuilderImpl<>();
        }

        @Override
        public <T> InitialPhaseBuilderImpl<T> newInitialPhaseBuilder(ArgCastBuilder<?, ?> currentBuilder) {
            return new InitialPhaseBuilderImpl<>(currentBuilder.state());
        }

        @Override
        public <T extends RAbstractVector, S> CoercedPhaseBuilderImpl<T, S> newCoercedPhaseBuilder(ArgCastBuilder<?, ?> currentBuilder, Class<?> elementClass) {
            return new CoercedPhaseBuilderImpl<>(currentBuilder.state(), elementClass);
        }

        @Override
        public <T> HeadPhaseBuilderImpl<T> newHeadPhaseBuilder(ArgCastBuilder<?, ?> currentBuilder) {
            return new HeadPhaseBuilderImpl<>(currentBuilder.state());
        }

        public final class InitialPhaseBuilderImpl<T> extends ArgCastBuilderBase<T, InitialPhaseBuilder<T>> implements InitialPhaseBuilder<T> {
            InitialPhaseBuilderImpl(ArgCastBuilderState state) {
                super(new ArgCastBuilderState(state, false));
            }

        }

        public final class PreinitialPhaseBuilderImpl<T> extends ArgCastBuilderBase<T, InitialPhaseBuilder<T>> implements PreinitialPhaseBuilder<T> {

            PreinitialPhaseBuilderImpl() {
                super(new ArgCastBuilderState(argumentIndex, argumentName, ArgCastBuilderFactoryImpl.this, CastBuilder.this, false));

                if (argumentIndex >= pipelineCfgBuilders.length) {
                    pipelineCfgBuilders = Arrays.copyOf(pipelineCfgBuilders, argumentIndex + 1);
                }

                pipelineCfgBuilders[argumentIndex] = new PipelineConfigBuilder(state());
            }

            @Override
            public PipelineConfigBuilder getPipelineConfigBuilder() {
                return pipelineCfgBuilders[argumentIndex];
            }

        }

        public final class CoercedPhaseBuilderImpl<T extends RAbstractVector, S> extends ArgCastBuilderBase<T, CoercedPhaseBuilder<T, S>> implements CoercedPhaseBuilder<T, S> {

            private final Class<?> elementClass;

            CoercedPhaseBuilderImpl(ArgCastBuilderState state, Class<?> elementClass) {
                super(new ArgCastBuilderState(state, true));
                this.elementClass = elementClass;
            }

            @Override
            public Class<?> elementClass() {
                return elementClass;
            }
        }

        public final class HeadPhaseBuilderImpl<T> extends ArgCastBuilderBase<T, HeadPhaseBuilder<T>> implements HeadPhaseBuilder<T> {
            HeadPhaseBuilderImpl(ArgCastBuilderState state) {
                super(new ArgCastBuilderState(state, false));
            }
        }

    }

    public static final class ChainBuilder<T> {
        private final Function<ArgCastBuilder<T, ?>, CastNode> firstCastNodeFactory;

        private ChainBuilder(Function<ArgCastBuilder<T, ?>, CastNode> firstCastNodeFactory) {
            this.firstCastNodeFactory = firstCastNodeFactory;
        }

        private Function<ArgCastBuilder<T, ?>, CastNode> makeChain(Function<ArgCastBuilder<T, ?>, CastNode> secondCastNodeFactory) {
            return phaseBuilder -> {
                return new ChainedCastNode(() -> firstCastNodeFactory.apply(phaseBuilder), () -> secondCastNodeFactory.apply(phaseBuilder));
            };
        }

        @SuppressWarnings("overloads")
        public ChainBuilder<T> with(Function<ArgCastBuilder<T, ?>, CastNode> secondCastNodeFactory) {
            return new ChainBuilder<>(makeChain(secondCastNodeFactory));
        }

        @SuppressWarnings("overloads")
        public ChainBuilder<T> with(ArgumentMapper<?, ?> mapper) {
            return with(Predef.map(mapper));
        }

        public ChainBuilder<T> with(CastNode secondCastNode) {
            return new ChainBuilder<>(makeChain(pb -> secondCastNode));
        }

        public Function<ArgCastBuilder<T, ?>, CastNode> end() {
            return firstCastNodeFactory;
        }

    }

    public static final class FindFirstNodeBuilder<T> {
        private final RBaseNode callObj;
        private final Message message;
        private final Object[] messageArgs;

        private FindFirstNodeBuilder(RBaseNode callObj, Message message, Object[] messageArgs) {
            this.callObj = callObj;
            this.message = message;
            this.messageArgs = messageArgs;
        }

        private Function<ArgCastBuilder<T, ?>, CastNode> create(Class<?> elementClass, Object defaultValue) {
            return phaseBuilder -> {
                Message actualMessage = message;
                Object[] actualMessageArgs = messageArgs;
                RBaseNode actualCallObj = callObj;
                if (message == null) {
                    DefaultError err = phaseBuilder.state().isDefaultErrorDefined() ? phaseBuilder.state().defaultError() : new DefaultError(null, RError.Message.LENGTH_ZERO);
                    actualMessage = err.message;
                    actualMessageArgs = err.args;
                    actualCallObj = err.callObj;
                }
                return FindFirstNodeGen.create(elementClass, actualCallObj, actualMessage, actualMessageArgs, defaultValue);
            };
        }

        public Function<ArgCastBuilder<T, ?>, CastNode> logicalElement() {
            return create(Byte.class, null);
        }

        public Function<ArgCastBuilder<T, ?>, CastNode> logicalElement(byte defaultValue) {
            return create(Byte.class, defaultValue);
        }

        public Function<ArgCastBuilder<T, ?>, CastNode> doubleElement() {
            return create(Double.class, null);
        }

        public Function<ArgCastBuilder<T, ?>, CastNode> doubleElement(double defaultValue) {
            return create(Double.class, defaultValue);
        }

        public Function<ArgCastBuilder<T, ?>, CastNode> integerElement() {
            return create(Integer.class, null);
        }

        public Function<ArgCastBuilder<T, ?>, CastNode> integerElement(int defaultValue) {
            return create(Integer.class, defaultValue);
        }

        public Function<ArgCastBuilder<T, ?>, CastNode> stringElement() {
            return create(String.class, null);
        }

        public Function<ArgCastBuilder<T, ?>, CastNode> stringElement(String defaultValue) {
            return create(String.class, defaultValue);
        }

        public Function<ArgCastBuilder<T, ?>, CastNode> objectElement() {
            return create(Object.class, null);
        }

        public Function<ArgCastBuilder<T, ?>, CastNode> objectElement(Object defaultValue) {
            return create(Object.class, defaultValue);
        }

    }

    public static final class PipelineConfigBuilder {

        private final ArgCastBuilderState state;

        private ArgumentMapper<? super RMissing, ?> missingMapper = null;
        private ArgumentMapper<? super RNull, ?> nullMapper = null;
        private DefaultError missingMsg;
        private DefaultError nullMsg;

        public PipelineConfigBuilder(ArgCastBuilderState state) {
            this.state = state;
            missingMsg = state.defaultError();
            nullMsg = state.defaultError();
        }

        public String getArgName() {
            return state.argumentName;
        }

        public ArgumentMapper<? super RMissing, ?> getMissingMapper() {
            return missingMapper;
        }

        public ArgumentMapper<? super RNull, ?> getNullMapper() {
            return nullMapper;
        }

        public DefaultError getMissingMessage() {
            return missingMsg;
        }

        public DefaultError getNullMessage() {
            return nullMsg;
        }

        public PipelineConfigBuilder mustNotBeMissing(RBaseNode callObj, RError.Message errorMsg, Object... msgArgs) {
            missingMapper = null;
            missingMsg = new DefaultError(callObj, errorMsg, msgArgs);
            return this;
        }

        public PipelineConfigBuilder mapMissing(ArgumentMapper<? super RMissing, ?> mapper) {
            missingMapper = mapper;
            missingMsg = null;
            return this;
        }

        public PipelineConfigBuilder mapMissing(ArgumentMapper<? super RMissing, ?> mapper, RBaseNode callObj, RError.Message warningMsg, Object... msgArgs) {
            missingMapper = mapper;
            missingMsg = new DefaultError(callObj, warningMsg, msgArgs);
            return this;
        }

        public PipelineConfigBuilder allowMissing() {
            return mapMissing(Predef.missingConstant());
        }

        public PipelineConfigBuilder allowMissing(RBaseNode callObj, RError.Message warningMsg, Object... msgArgs) {
            return mapMissing(Predef.missingConstant(), callObj, warningMsg, msgArgs);
        }

        public PipelineConfigBuilder mustNotBeNull(RBaseNode callObj, RError.Message errorMsg, Object... msgArgs) {
            nullMapper = null;
            nullMsg = new DefaultError(callObj, errorMsg, msgArgs);
            return this;
        }

        public PipelineConfigBuilder mapNull(ArgumentMapper<? super RNull, ?> mapper) {
            nullMapper = mapper;
            nullMsg = null;
            return this;
        }

        public PipelineConfigBuilder mapNull(ArgumentMapper<? super RNull, ?> mapper, RBaseNode callObj, RError.Message warningMsg, Object... msgArgs) {
            nullMapper = mapper;
            nullMsg = new DefaultError(callObj, warningMsg, msgArgs);
            return this;
        }

        public PipelineConfigBuilder allowNull() {
            return mapNull(Predef.nullConstant());
        }

        public PipelineConfigBuilder allowNull(RBaseNode callObj, RError.Message warningMsg, Object... msgArgs) {
            return mapNull(Predef.nullConstant(), callObj, warningMsg, msgArgs);
        }

        void updateDefaultError() {
            missingMsg = state.defaultError();
            nullMsg = state.defaultError();
        }

    }

}
