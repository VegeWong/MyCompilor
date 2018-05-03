package com.vegw.compiler.Type;

public class StringType extends Type{

    @Override
    public boolean isSameType(Type a) {
        if (this == a) return true;
        return (a instanceof StringType);
    }

    @Override
    public String toString() { return "string"; }

}
