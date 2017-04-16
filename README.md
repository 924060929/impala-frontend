# impala-frontend
这个库是[Impala](https://github.com/cloudera/Impala)前端库，增加语法块位置信息的功能

impala版本：[cdh5.10.0-release](https://github.com/cloudera/Impala/tree/cdh5.10.0-release)


## 使用方法:
```
import java_cup.runtime.ExtendSymbolFactory;
import org.apache.impala.analysis.*;

import java.io.StringReader;

/**
 * Created by lanhuajian on 2017/4/15.
 */
public class Test {
    public static void main(String[] args) throws Exception {
        new Test().testNew();
    }

    private void testNew() throws Exception {

        String sql = "SELECT concat('1', '2') " +
            "FROM fact_order o " +
            "LEFT JOIN dim_business d " +
            "WHERE o.shop_id = d.shop_id " +
            "  AND o.order_day_key>20170415";
        SqlScanner scanner = new SqlScanner(new StringReader(sql));

        SqlParser parser = new SqlParser(scanner, new ExtendSymbolFactory(sql));
        SelectStmt selectStmt = (SelectStmt) parser.parse().value;
        SelectList selectList = selectStmt.getSelectList();
        SelectListItem selectListItem = selectList.getItems().get(0);
        Expr expr = selectListItem.getExpr();
        Expr whereClause = selectStmt.getWhereClause();
        System.out.println("start: " + expr.startPosition + ", end: " + expr.endPosition);
    }
}
```
