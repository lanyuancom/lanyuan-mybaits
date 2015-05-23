/*
 *    Copyright 2009-2012 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.plugin;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;

import org.apache.commons.lang.StringUtils;
import org.apache.ibatis.io.ResolverUtil;
import org.apache.ibatis.jdbc.SqlRunner;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.reflection.ExceptionUtil;
import org.apache.ibatis.session.Configuration;

import com.lanyuan.plugin.PagePlugin;
import com.lanyuan.plugin.PageView;
import com.lanyuan.util.Common;

/**
 * @author Clinton Begin
 */
public class Plugin implements InvocationHandler {

  private Object target;
  private Interceptor interceptor;
  private Map<Class<?>, Set<Method>> signatureMap;

  private Plugin(Object target, Interceptor interceptor, Map<Class<?>, Set<Method>> signatureMap) {
    this.target = target;
    this.interceptor = interceptor;
    this.signatureMap = signatureMap;
  }

  public static Object wrap(Object target, Interceptor interceptor) {
    Map<Class<?>, Set<Method>> signatureMap = getSignatureMap(interceptor);
    Class<?> type = target.getClass();
    Class<?>[] interfaces = getAllInterfaces(type, signatureMap);
    if (interfaces.length > 0) {
      return Proxy.newProxyInstance(
          type.getClassLoader(),
          interfaces,
          new Plugin(target, interceptor, signatureMap));
    }
    return target;
  }

  public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
    try {
      Set<Method> methods = signatureMap.get(method.getDeclaringClass());
      if (methods != null && methods.contains(method)) {
        return interceptor.intercept(new Invocation(target, method, args));
      }
      return method.invoke(target, args);
    } catch (Exception e) {
      throw ExceptionUtil.unwrapThrowable(e);
    }
  }

  private static Map<Class<?>, Set<Method>> getSignatureMap(Interceptor interceptor) {
    Intercepts interceptsAnnotation = interceptor.getClass().getAnnotation(Intercepts.class);
    if (interceptsAnnotation == null) { // issue #251
      throw new PluginException("No @Intercepts annotation was found in interceptor " + interceptor.getClass().getName());      
    }
    Signature[] sigs = interceptsAnnotation.value();
    Map<Class<?>, Set<Method>> signatureMap = new HashMap<Class<?>, Set<Method>>();
    for (Signature sig : sigs) {
      Set<Method> methods = signatureMap.get(sig.type());
      if (methods == null) {
        methods = new HashSet<Method>();
        signatureMap.put(sig.type(), methods);
      }
      try {
        Method method = sig.type().getMethod(sig.method(), sig.args());
        methods.add(method);
      } catch (NoSuchMethodException e) {
        throw new PluginException("Could not find method on " + sig.type() + " named " + sig.method() + ". Cause: " + e, e);
      }
    }
    return signatureMap;
  }

  private static Class<?>[] getAllInterfaces(Class<?> type, Map<Class<?>, Set<Method>> signatureMap) {
    Set<Class<?>> interfaces = new HashSet<Class<?>>();
    while (type != null) {
      for (Class<?> c : type.getInterfaces()) {
        if (signatureMap.containsKey(c)) {
          interfaces.add(c);
        }
      }
      type = type.getSuperclass();
    }
    return interfaces.toArray(new Class<?>[interfaces.size()]);
  }

	public static final String joinSql( Connection connection, MappedStatement mappedStatement, BoundSql boundSql, Map<String, Object> formMap, List<Object> formMaps)throws Exception{
		Object table = null;
		String sql = "";
		if(null!=formMap){
			table = formMap.get(Configuration.LY_TABLE);
			sql=" SELECT * FROM "+table.toString().toUpperCase();
		}
		String sqlId = mappedStatement.getId();
		sqlId = sqlId.substring(sqlId.lastIndexOf(".")+1);
		if(Configuration.FINDBYWHERE.equals(sqlId)){
			if(null!=formMap.get("where")&&!StringUtils.isBlank(formMap.get("where").toString())){
				sql +=" "+formMap.get("where").toString(); 
			}
		}else if(Configuration.FINDBYPAGE.equals(sqlId)){
			Map<String, Object> mapfield = SqlRunner.findByTablefield(connection, boundSql, table.toString());
			String field = mapfield.get(Configuration.FIELD).toString();
			String[] fe = field.split(",");
			String param = "";
			for (String string : fe) {
				if(formMap.containsKey(string)){
					Object v =formMap.get(string);
					if(v.toString().indexOf("%")>-1)
					{
						param += " AND "+string+" like '"+v+"'";
					}else{
						if(ResolverUtil.isNumeric1(v.toString())){
							param += " AND "+string+" = "+v;
						}else{
							param += " AND "+string+" = '"+v+"'";
						}
					}
				}
			}
			if(StringUtils.isNotBlank(param)){
				param=param.substring(param.indexOf("AND")+4);
				sql+=" WHERE "+param;
			}
			Object by = formMap.get("$orderby");
			if(null!=by){
				sql+= " "+by;
			}
			Object paging=formMap.get("paging");
			if(null==paging){
				throw new Exception("调用findByPage接口,必须传入PageView对象! formMap.set(\"paging\", pageView);");
			}else if(StringUtils.isBlank(paging.toString())){
				throw new Exception("调用findByPage接口,必须传入PageView对象! formMap.set(\"paging\", pageView);");
			}
			PageView pageView = (PageView) paging;
			PagePlugin.setCount(sql, connection, boundSql, pageView);
			sql=PagePlugin.generatePagesSql(sql, pageView);
		}else if(Configuration.DELETEBYNAMES.equals(sqlId)){
			sql = "delete from "+table.toString()+" where ";
			String param = "";
			for (Entry<String, Object> entry : formMap.entrySet()) {
				if(!"ly_table".equals(entry.getKey())&&null!=entry.getValue()&&!"_t".equals(entry.getKey()))
				param += " AND "+entry.getKey()+" IN ("+entry.getValue()+")";
			}
			if(StringUtils.isNotBlank(param)){
				param=param.substring(param.indexOf("AND")+4);
				sql+=param;
			}
		}else if(Configuration.DELETEBYATTRIBUTE.equals(sqlId)){
			sql = "DELETE FROM "+table.toString()+" WHERE "+formMap.get("key");
			if(null!=formMap.get("value"))
			{
				sql += " IN ("+formMap.get("value")+")";
			}
		}else if(Configuration.FINDBYNAMES.equals(sqlId)){
			Map<String, Object> mapfield = SqlRunner.findByTablefield(connection, boundSql, table.toString());
			String field = mapfield.get(Configuration.FIELD).toString();
			String[] fe = field.split(",");
			String param = "";
			for (String string : fe) {
				if(formMap.containsKey(string)){
					Object v =formMap.get(string);
					if(v.toString().indexOf("%")>-1)//处理模糊查询
					{
						param += " AND "+string+" LIKE '"+v+"'";
					}else{
						if(ResolverUtil.isNumeric1(v.toString())){
							param += " AND "+string+" = "+v;
						}else{
							param += " AND "+string+" = '"+v+"'";
						}
					}
				}
			}
			if(StringUtils.isNotBlank(param)){
				param=param.substring(param.indexOf("AND")+4);
				sql+=" WHERE "+param;
				
			}
			Object by = formMap.get("$orderby");
			if(null!=by){
				sql+= " "+by;
			}
		}else if(Configuration.FINDBYATTRIBUTE.equals(sqlId)){
			sql = "SELECT * FROM "+table.toString()+" WHERE "+formMap.get("key");
			if(null!=formMap.get("value")&&formMap.get("value").toString().indexOf("%")>-1)//处理模糊查询
			{
				sql += " LIKE '"+formMap.get("value")+"'";
			}else{
				Object v = formMap.get("value");
				if(ResolverUtil.isNumeric1(v.toString())){
					sql += " IN ("+v+")";
				}else{
					sql += " IN ('"+v+"')";
				}
				
			}
		}else if(Configuration.ADDENTITY.equals(sqlId)){
			Map<String, Object> mapfield = SqlRunner.findByTablefield(connection, boundSql, table.toString());
			String field = mapfield.get(Configuration.FIELD).toString();
			String[] fe = field.split(",");
			String fieldString = "";
			String fieldValues = "";
			for (String string : fe) {
				Object v=formMap.get(string);
				if(null!=v&&!StringUtils.isBlank(v.toString())){
					fieldString+=string+",";
					fieldValues+="'"+v+"',";
				}
			}
			sql = "INSERT INTO "+table.toString()+" ("+ResolverUtil.trimComma(fieldString)+")  values ("+ResolverUtil.trimComma(fieldValues)+")";
		}else if(Configuration.EDITENTITY.equals(sqlId)){
			Map<String, Object> mapfield = SqlRunner.findByTablefield(connection, boundSql, table.toString());
			String field = mapfield.get(Configuration.FIELD).toString();
			String[] fe = field.split(",");
			String fieldString = "";
			String where = "";
			for (String string : fe) {
				Object v=formMap.get(string);
				if(null!=v&&!StringUtils.isBlank(v.toString())){
					String key = mapfield.get(Configuration.COLUMN_KEY).toString();
					if(!StringUtils.isBlank(key)){
						if(key.equals(string)){
							where = "WHERE "+key+" = '"+v+"'";
						}else{
							fieldString+=string+"='"+v+"',";
						}
					}else{
						throw new NullPointerException("update操作没有找到主键!");
					}
					
				}
			}
			
			sql = "UPDATE "+table.toString()+" SET "+ResolverUtil.trimComma(fieldString)+" "+where;
		}else if(Configuration.FINDBYFRIST.equals(sqlId)){
			sql = "SELECT * FROM "+table.toString()+" WHERE "+formMap.get("key");
			if(null!=formMap.get("value")&&!"".equals(formMap.get("value").toString()))//处理模糊查询
			{
				sql += " = '"+formMap.get("value")+"'";
			}else{
				throw new Exception(sqlId+" 调用公共方法异常!,传入参数错误！");
			}
			
		}else if(Configuration.BATCHSAVE.equals(sqlId)){
			Map<String, Object> mapfield = null;
			String field = null;
			if(null!=formMaps&&formMaps.size()>0){
				table = Common.toHashMap(formMaps.get(0)).get(Configuration.LY_TABLE);
				mapfield=SqlRunner.findByTablefield(connection, boundSql, table.toString());
				field=mapfield.get(Configuration.FIELD).toString();
			}
			sql = "INSERT INTO "+table.toString();
			String fieldString = "";
			String fs = "";
			String fd = "";
			String fieldValues = "";
			String fvs = "";
			for (int i = 0; i < formMaps.size(); i++) {
				Object object = formMaps.get(i);
				Map<String, Object> froMmap = (Map<String, Object>) object;
				String[] fe = field.split(",");
				for (String string : fe) {
					Object v=froMmap.get(string);
					if(null!=v&&!StringUtils.isBlank(v.toString())){
						fieldString+=string+",";
						fieldValues+="'"+v+"',";
					}
				}
				if(i==0){
					fd=fieldString;
				}
				fvs +="("+ResolverUtil.trimComma(fieldValues)+"),";
				fs +=" INSERT INTO "+table.toString()+" ("+ResolverUtil.trimComma(fieldString)+")  VALUES ("+ResolverUtil.trimComma(fieldValues)+");";
				fieldValues = "";
				fieldString= "";
			}
			String v = ResolverUtil.trimComma(fvs);
			sql = "INSERT INTO "+table.toString()+" ("+ResolverUtil.trimComma(fd)+")  VALUES "+ResolverUtil.trimComma(fvs)+"";
			String[] vs = v.split("\\),");
			boolean b = false;
			for (String string : vs) {
				if(string.split(",").length!=fd.split(",").length){
					b=true;
				}
			}
			if(b){
				sql = fs.substring(0,fs.length()-1);
			}
			
		}else{
			throw new Exception("调用公共方法异常!");
		}
		return sql.toUpperCase();
	}
}
