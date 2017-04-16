# impala-frontend
这个库是[Impala](https://github.com/cloudera/Impala)前端库，增加语法块位置信息的功能

impala版本：[cdh5.10.0-release](https://github.com/cloudera/Impala/tree/cdh5.10.0-release)

## 安装方法
把fe文件夹替换到`Impala-cdhx.x.x-release`目录中，运行`./buildall.sh -fe_only`，编译完成后复制`fe/target/impala-frontend-0.1-SNAPSHOT.jar`到工程中使用

## 使用方法:
```
import java_cup.runtime.ExtendSymbolFactory;
import org.apache.impala.analysis.*;

import java.io.StringReader;

public class Test {
    public static void main(String[] args) throws Exception {
        String sql = "SELECT concat(o.order_id, '2') " +
            "FROM fact_order o " +
            "LEFT JOIN dim_business d " +
            "WHERE o.shop_id = d.shop_id " +
            "  AND o.order_day_key=20170415";

        SqlScanner scanner = new SqlScanner(new StringReader(sql));
        SqlParser parser = new SqlParser(scanner, new ExtendSymbolFactory(sql));
        SelectStmt selectStmt = (SelectStmt) parser.parse().value;
        SelectList selectList = selectStmt.getSelectList();
        SelectListItem selectListItem = selectList.getItems().get(0);
        Expr expr = selectListItem.getExpr();

        // 输出结果：start: 7, end: 30
        System.out.println("start: " + expr.startPosition + ", end: " + expr.endPosition);
    }
}
```
#### maven依赖：
```
<dependency>
    <groupId>org.apache.hive</groupId>
    <artifactId>hive-exec</artifactId>
    <version>0.14.0</version>
</dependency>

<dependency>
    <groupId>org.apache.sentry</groupId>
    <artifactId>sentry-core-model-db</artifactId>
    <version>1.5.1-cdh5.10.0</version>
    <exclusions>
        <exclusion>
            <groupId>org.apache.hadoop</groupId>
            <artifactId>hadoop-common</artifactId>
        </exclusion>
    </exclusions>
</dependency>

<dependency>
    <groupId>org.apache.impala</groupId>
    <artifactId>impala-frontend</artifactId>
    <version>cdh5.10.0-release</version>
    <scope>system</scope>
    <!-- 这里改成你的jar包目录 -->
    <systemPath>${project.basedir}/src/main/lib/impala-frontend-0.1-SNAPSHOT.jar</systemPath>
</dependency>
```
