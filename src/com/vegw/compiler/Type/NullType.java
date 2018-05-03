package com.vegw.compiler.Type;

public class NullType extends Type {

    @Override
    public boolean isConvertable(Type t) {
        return t.isConvertable(Type.NULL);
    }

    @Override
    public boolean isSameType(Type a) {
        if (this == a) return true;
        return (a instanceof NullType);
    }

    @Override
    public String toString() { return "null"; }

}
