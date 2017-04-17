package org.apache.impala.util;

import java_cup.runtime.ExtendSymbolFactory;
import org.apache.impala.analysis.SqlParser;
import org.apache.impala.analysis.SqlScanner;
import org.apache.impala.analysis.SyntaxBlock;

import java.io.StringReader;
import java.util.Stack;

/**
 * Created by lanhuajian on 2017/4/17.
 */
public class ImpalaSqlUtil {
    public static SyntaxBlock parse(String sql) throws Exception {
        SqlScanner scanner = new SqlScanner(new StringReader(sql));
        SqlParser parser = new SqlParser(scanner, new ExtendSymbolFactory(sql));
        return (SyntaxBlock) parser.parse().value;
    }

    public static void foreach(SyntaxBlock rootBlock, ForeachAction action) {
        if (rootBlock == null || action == null) {
            return;
        }
        action.doAction(rootBlock, new Stack<SyntaxBlock>(), 0);
        foreachSubBlocks(rootBlock, action, new Stack<SyntaxBlock>());
    }

    private static void foreachSubBlocks(SyntaxBlock parentSyntaxBlock, ForeachAction action, Stack<SyntaxBlock> stack) {
        if (parentSyntaxBlock.subBlocks != null && parentSyntaxBlock.subBlocks.size() > 0) {
            stack.push(parentSyntaxBlock);
            for (int i = 0; i < parentSyntaxBlock.subBlocks.size(); i++) {
                SyntaxBlock subBlock = parentSyntaxBlock.subBlocks.get(i);
                if (subBlock != null) {
                    Stack<SyntaxBlock> copyStack = new Stack<>();
                    copyStack.addAll(stack);
                    action.doAction(subBlock, copyStack, i);
                    foreachSubBlocks(subBlock, action, stack);
                }
            }
            stack.pop();
        }
    }

    public interface ForeachAction {
        void doAction(SyntaxBlock syntaxBlock, Stack<SyntaxBlock> parentStack, int childIndex);
    }
}
