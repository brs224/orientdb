/* Generated By:JJTree: Do not edit this line. OStatement.java Version 4.3 */
/* JavaCCOptions:MULTI=true,NODE_USES_PARSER=false,VISITOR=true,TRACK_TOKENS=true,NODE_PREFIX=O,NODE_EXTENDS=,NODE_FACTORY=,SUPPORT_CLASS_VISIBILITY_PUBLIC=true */
package com.orientechnologies.orient.core.sql.parser;

import com.orientechnologies.common.listener.OProgressListener;
import com.orientechnologies.orient.core.command.OCommandContext;
import com.orientechnologies.orient.core.record.impl.ODocument;
import com.orientechnologies.orient.core.sql.OCommandSQLParsingException;
import com.orientechnologies.orient.core.sql.query.OSQLAsynchQuery;

import java.util.Map;

public class OStatement extends SimpleNode {

  public static final String CUSTOM_STRICT_SQL = "strictSql";

  public OStatement(int id) {
    super(id);
  }

  public OStatement(OrientSql p, int id) {
    super(p, id);
  }

  /** Accept the visitor. **/
  public Object jjtAccept(OrientSqlVisitor visitor, Object data) {
    return visitor.visit(this, data);
  }

  public void toString(Map<Object, Object> params, StringBuilder builder) {
    throw new UnsupportedOperationException("missing implementation in " + getClass().getSimpleName());
  }

  public void validate(OrientSql.ValidationStats stats) throws OCommandSQLParsingException {

  }

  @Override
  public String toString(String prefix) {
    StringBuilder builder = new StringBuilder();
    toString(null, builder);
    return builder.toString();
  }

  public Object execute(OSQLAsynchQuery<ODocument> request, OCommandContext context, OProgressListener progressListener) {
    throw new UnsupportedOperationException("Unsupported command: " + getClass().getSimpleName());
  }
}
/* JavaCC - OriginalChecksum=589c4dcc8287f430e46d8eb12b0412c5 (do not edit this line) */