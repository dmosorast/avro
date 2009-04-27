/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.avro.reflect;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import org.apache.avro.AvroRuntimeException;
import org.apache.avro.AvroTypeException;
import org.apache.avro.Protocol;
import org.apache.avro.Schema;
import org.apache.avro.Protocol.Message;
import org.apache.avro.Schema.Type;
import org.apache.avro.generic.GenericArray;
import org.apache.avro.ipc.AvroRemoteException;
import org.apache.avro.util.Utf8;

import com.thoughtworks.paranamer.CachingParanamer;
import com.thoughtworks.paranamer.Paranamer;

/** Utilities to use existing Java classes and interfaces via reflection. */
public class ReflectData {
  private ReflectData() {}
  
  /** Returns true if an object matches a schema. */
  public static boolean validate(Schema schema, Object datum) {
    switch (schema.getType()) {
    case RECORD:
      Class recordClass = datum.getClass(); 
      if (!(datum instanceof Object)) return false;
      for (Map.Entry<String, Schema> entry : schema.getFieldSchemas()) {
        try {
          if (!validate(entry.getValue(),
                        recordClass.getField(entry.getKey()).get(datum)))
          return false;
        } catch (NoSuchFieldException e) {
          return false;
        } catch (IllegalAccessException e) {
          throw new AvroRuntimeException(e);
        }
      }
      return true;
    case ARRAY:
      if (!(datum instanceof GenericArray)) return false;
      for (Object element : (GenericArray)datum)
        if (!validate(schema.getElementType(), element))
          return false;
      return true;
    case UNION:
      for (Schema type : schema.getTypes())
        if (validate(type, datum))
          return true;
      return false;
    case STRING:  return datum instanceof Utf8;
    case BYTES:   return datum instanceof ByteBuffer;
    case INT:     return datum instanceof Integer;
    case LONG:    return datum instanceof Long;
    case FLOAT:   return datum instanceof Float;
    case DOUBLE:  return datum instanceof Double;
    case BOOLEAN: return datum instanceof Boolean;
    case NULL:    return datum == null;
    default: return false;
    }
  }

  private static final WeakHashMap<java.lang.reflect.Type,Schema> SCHEMA_CACHE =
    new WeakHashMap<java.lang.reflect.Type,Schema>();

  /** Generate a schema for a Java type.
   * <p>For records, {@link Class#getDeclaredFields() declared fields} (not
   * inherited) which are not static or transient are used.</p>
   * <p>Note that unions cannot be automatically generated by this method,
   * since Java provides no representation for unions.</p>
   */
  public static Schema getSchema(java.lang.reflect.Type type) {
    Schema schema = SCHEMA_CACHE.get(type);
    if (schema == null) {
      schema = createSchema(type, new HashMap<String,Schema>());
      SCHEMA_CACHE.put(type, schema);
    }
    return schema;
  }

  @SuppressWarnings(value="unchecked")
  private static Schema createSchema(java.lang.reflect.Type type,
                                     Map<String,Schema> names) {
    if (type == Utf8.class)
      return Schema.create(Type.STRING);
    else if (type == ByteBuffer.class)
      return Schema.create(Type.BYTES);
    else if ((type == Integer.class) || (type == Integer.TYPE))
      return Schema.create(Type.INT);
    else if ((type == Long.class) || (type == Long.TYPE))
      return Schema.create(Type.LONG);
    else if ((type == Float.class) || (type == Float.TYPE))
      return Schema.create(Type.FLOAT);
    else if ((type == Double.class) || (type == Double.TYPE))
      return Schema.create(Type.DOUBLE);
    else if ((type == Boolean.class) || (type == Boolean.TYPE))
      return Schema.create(Type.BOOLEAN);
    else if ((type == Void.class) || (type == Void.TYPE))
      return Schema.create(Type.NULL);
    else if (type instanceof ParameterizedType) {
      ParameterizedType ptype = (ParameterizedType)type;
      Class raw = (Class)ptype.getRawType();
      System.out.println("ptype = "+ptype+" raw = "+raw);
      java.lang.reflect.Type[] params = ptype.getActualTypeArguments();
      for (int i = 0; i < params.length; i++)
        System.out.println("param ="+params[i]);
      if (GenericArray.class.isAssignableFrom(raw)) { // array
        if (params.length != 1)
          throw new AvroTypeException("No array type specified.");
        return Schema.createArray(createSchema(params[0], names));
      } else if (Map.class.isAssignableFrom(raw)) { // map
        java.lang.reflect.Type key = params[0];
        java.lang.reflect.Type value = params[1];
        if (!(key == Utf8.class))
          throw new AvroTypeException("Map key class not Utf8: "+key);
        return Schema.createMap(createSchema(value, names));
      }
    } else if (type instanceof Class) {             // record
      Class c = (Class)type;
      String name = c.getSimpleName();            // FIXME: ignoring package
      Schema schema = names.get(name);
      if (schema == null) {
        Map<String,Schema> fields = new LinkedHashMap<String,Schema>();
        schema = Schema.createRecord(name, c.getPackage().getName(),
                                     Throwable.class.isAssignableFrom(c));
        if (!names.containsKey(name))
          names.put(name, schema);
        for (Field field : c.getDeclaredFields())
          if ((field.getModifiers()&(Modifier.TRANSIENT|Modifier.STATIC))==0)
            fields.put(field.getName(),
                       createSchema(field.getGenericType(), names));
        schema.setFields(fields);
      }
      return schema;
    }
    throw new AvroTypeException("Unknown type: "+type);
  }

  /** Generate a protocol for a Java interface.
   * <p>Note that this requires that <a
   * href="http://paranamer.codehaus.org/">Paranamer</a> is run over compiled
   * interface declarations, since Java 6 reflection does not provide access to
   * method parameter names.  See Avro's build.xml for an example. </p>
   */
  public static Protocol getProtocol(Class iface) {
    Protocol protocol =
      new Protocol(iface.getSimpleName(), iface.getPackage().getName()); 
    for (Method method : iface.getDeclaredMethods())
      if ((method.getModifiers() & Modifier.STATIC) == 0)
        protocol.getMessages().put(method.getName(),
                                   getMessage(method, protocol));
    return protocol;
  }

  private static Paranamer PARANAMER = new CachingParanamer();

  private static Message getMessage(Method method, Protocol protocol) {
    Map<String,Schema> names = protocol.getTypes();
    Map<String,Schema> fields = new LinkedHashMap<String,Schema>();
    String[] paramNames = PARANAMER.lookupParameterNames(method);
    java.lang.reflect.Type[] paramTypes = method.getGenericParameterTypes();
    for (int i = 0; i < paramTypes.length; i++)
      fields.put(paramNames[i], createSchema(paramTypes[i], names));
    Schema request = Schema.createRecord(fields);

    Schema response = createSchema(method.getGenericReturnType(), names);

    List<Schema> errs = new ArrayList<Schema>();
    errs.add(Protocol.SYSTEM_ERROR);              // every method can throw
    for (java.lang.reflect.Type err : method.getGenericExceptionTypes())
      if (err != AvroRemoteException.class) 
        errs.add(createSchema(err, names));
    Schema errors = Schema.createUnion(errs);

    return protocol.createMessage(method.getName(), request, response, errors);
  }

}
