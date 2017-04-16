package java_cup.runtime;

import java_cup.runtime.Symbol;

/**
 * Created by lanhuajian on 2017/4/12.
 */
public class ExtendSymbol extends Symbol {
    public int start;
    public int end;
    public String text;

    public ExtendSymbol(int id, int left, int right, Object value,
                        int start, int end, String text) {
        super(id, left, right, value);
        this.start = start;
        this.end = end;
        this.text = text;
    }

    public ExtendSymbol(int id, ExtendSymbol left, ExtendSymbol right, Object value) {
        this(id, left.left, right.right, value, left.start, right.end, null);
    }

    public ExtendSymbol(int id, ExtendSymbol left, ExtendSymbol right) {
        this(id, left, right, null);
    }

    public ExtendSymbol(int id, int left, int right, Object value) {
        this(id, left, right, value, -1, -1, null);
    }

    public ExtendSymbol(int id, Object o) {
        this(id, -1, -1, o);
    }

    public ExtendSymbol(int id, int left, int right) {
        this(id, left, right, (Object)null);
    }

    public ExtendSymbol(int sym_num) {
        super(sym_num, -1);
        this.start = -1;
        this.end = -1;
    }

    ExtendSymbol(int sym_num, int state) {
        super(sym_num, state);
        this.start = -1;
        this.end = -1;
    }
}
