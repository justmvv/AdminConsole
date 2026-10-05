package ru.ops.console.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** The view is available only if the section is enabled in {@code console.features}. See {@link FeatureGuard}. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface RequiresFeature {
    ConsoleProperties.Feature value();
}
