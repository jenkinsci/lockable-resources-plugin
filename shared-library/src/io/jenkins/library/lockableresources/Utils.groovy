#!groovy
package io.jenkins.library.lockableresources;

class Utils {

  public static def globalScope = null;

  //---------------------------------------------------------------------------
  /** */
  public static Map fixNullMap(Map map) {
    if (map == null) {
      return [:];
    }
    return map;
  }

  //---------------------------------------------------------------------------
  /** */
  public static echo(String msg) {
    if (globalScope == null) {
      return;
    }
    globalScope.echo(msg);
  }
}