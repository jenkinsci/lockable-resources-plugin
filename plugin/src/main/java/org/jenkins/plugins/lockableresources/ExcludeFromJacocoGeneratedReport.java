package org.jenkins.plugins.lockableresources;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.annotation.ElementType;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD,
         ElementType.TYPE,
         ElementType.ANNOTATION_TYPE,
         ElementType.CONSTRUCTOR})
public @interface ExcludeFromJacocoGeneratedReport {
}
