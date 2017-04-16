package org.apache.impala.analysis;

import java_cup.runtime.ExtendSymbol;

import java.util.List;

/**
 * Created by lanhuajian on 2017/4/12.
 */
public class ObjectSyntaxBlock<T> extends SyntaxBlock {
    public T objectValue;

    public ObjectSyntaxBlock() {
    }

    public ObjectSyntaxBlock(T objectValue) {
        this.objectValue = objectValue;
    }

    public ObjectSyntaxBlock(int startPosition, int endPosition, T objectValue) {
        super(startPosition, endPosition);
        this.objectValue = objectValue;
    }

    public ObjectSyntaxBlock(int startPosition, int endPosition, List<SyntaxBlock> subBlocks, T objectValue) {
        super(startPosition, endPosition, subBlocks);
        this.objectValue = objectValue;
    }

    public T getObjectValue() {
        return objectValue;
    }

    public static <T> SyntaxBlock valueOf(T t) {
        if (t == null) {
            return null;
        } else if (t instanceof SyntaxBlock) {
            return (SyntaxBlock) t;
        } else if (t instanceof ExtendSymbol) {
            ExtendSymbol extendSymbol = (ExtendSymbol) t;
            if (extendSymbol.value == null && extendSymbol.text != null) {
                return new ObjectSyntaxBlock<>(extendSymbol.start, extendSymbol.end, extendSymbol.text);
            } else {
                return valueOf(extendSymbol.value);
            }
        } else {
            return new ObjectSyntaxBlock<>(t);
        }
    }
}
