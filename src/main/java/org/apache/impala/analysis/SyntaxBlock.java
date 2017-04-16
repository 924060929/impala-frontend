package org.apache.impala.analysis;

import java.util.List;

/**
 * Created by lanhuajian on 2017/4/12.
 */
public class SyntaxBlock {
    public int startPosition = -1;
    public int endPosition = -1;
    public List<SyntaxBlock> subBlocks;

    public SyntaxBlock() {
    }

    public SyntaxBlock(int startPosition, int endPosition) {
        this.startPosition = startPosition;
        this.endPosition = endPosition;
    }

    public SyntaxBlock(int startPosition, int endPosition, List<SyntaxBlock> subBlocks) {
        this.startPosition = startPosition;
        this.endPosition = endPosition;
        this.subBlocks = subBlocks;
    }
}
