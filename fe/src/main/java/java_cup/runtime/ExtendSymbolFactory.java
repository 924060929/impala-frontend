package java_cup.runtime;

import java_cup.runtime.Symbol;
import java_cup.runtime.SymbolFactory;

/**
 * Created by lanhuajian on 2017/4/12.
 */
public class ExtendSymbolFactory implements SymbolFactory {
    private int firstNonWhitespaceIndex;

    public ExtendSymbolFactory(String sql) {
        for (int i = 0; i < sql.length(); i++) {
            if (!Character.isWhitespace(sql.charAt(i))) {
                firstNonWhitespaceIndex = i;
                break;
            }
        }
    }

    @Override
    public ExtendSymbol newSymbol(String name, int id, Symbol left, Symbol right, Object value) {
        return new ExtendSymbol(id, (ExtendSymbol) left, (ExtendSymbol) right, value);
    }

    @Override
    public ExtendSymbol newSymbol(String name, int id, Symbol left, Symbol right) {
        return new ExtendSymbol(id, (ExtendSymbol) left, (ExtendSymbol) right);
    }

    @Override
    public ExtendSymbol newSymbol(String name, int id, Object o) {
        return new ExtendSymbol(id, o);
    }

    @Override
    public ExtendSymbol newSymbol(String name, int id) {
        return new ExtendSymbol(id);
    }

    @Override
    public ExtendSymbol startSymbol(String name, int id, int state) {
        ExtendSymbol symbol = new ExtendSymbol(id, state);
        symbol.start = firstNonWhitespaceIndex;
        symbol.end = firstNonWhitespaceIndex;
        return symbol;
    }
}
