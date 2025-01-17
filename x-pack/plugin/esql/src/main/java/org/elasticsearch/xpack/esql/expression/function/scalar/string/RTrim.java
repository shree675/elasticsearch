/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.expression.function.scalar.string;

import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.UnicodeUtil;
import org.elasticsearch.compute.ann.Evaluator;
import org.elasticsearch.compute.operator.EvalOperator;
import org.elasticsearch.xpack.esql.expression.function.scalar.UnaryScalarFunction;
import org.elasticsearch.xpack.esql.planner.Mappable;
import org.elasticsearch.xpack.ql.expression.Expression;
import org.elasticsearch.xpack.ql.expression.TypeResolutions;
import org.elasticsearch.xpack.ql.tree.NodeInfo;
import org.elasticsearch.xpack.ql.tree.Source;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.elasticsearch.xpack.ql.expression.TypeResolutions.isString;

/**
 * Removes trailing whitespaces from a string.
 */
public class RTrim extends UnaryScalarFunction implements Mappable {
    public RTrim(Source source, Expression field) {
        super(source, field);
    }

    @Override
    protected TypeResolution resolveType() {
        if (childrenResolved() == false) {
            return new Expression.TypeResolution("Unresolved children");
        }

        return isString(field, sourceText(), TypeResolutions.ParamOrdinal.DEFAULT);
    }

    @Override
    public Object fold() {
        return Mappable.super.fold();
    }

    @Override
    public Supplier<EvalOperator.ExpressionEvaluator> toEvaluator(
        Function<Expression, Supplier<EvalOperator.ExpressionEvaluator>> toEvaluator
    ) {
        Supplier<EvalOperator.ExpressionEvaluator> field = toEvaluator.apply(field());
        return () -> new RTrimEvaluator(field.get());
    }

    @Override
    public Expression replaceChildren(List<Expression> newChildren) {
        return new RTrim(source(), newChildren.get(0));
    }

    @Override
    protected NodeInfo<? extends Expression> info() {
        return NodeInfo.create(this, RTrim::new, field());
    }

    @Evaluator
    static BytesRef process(final BytesRef val) {
        int offset = val.offset;
        int end = offset;
        UnicodeUtil.UTF8CodePoint codePoint = new UnicodeUtil.UTF8CodePoint();
        while (offset < val.offset + val.length) {
            codePoint = UnicodeUtil.codePointAt(val.bytes, offset, codePoint);
            if (Character.isWhitespace(codePoint.codePoint) == false) {
                end = offset + codePoint.numBytes;
            }
            offset += codePoint.numBytes;
        }

        return new BytesRef(val.bytes, val.offset, end - val.offset);
    }
}
