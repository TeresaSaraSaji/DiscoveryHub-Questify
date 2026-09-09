import { Signal, computed } from '@angular/core';

interface ReadableResource<T> {
  hasValue(): boolean;
  readonly value: Signal<T>;
}

/**
 * A resource's value, or a fallback whenever there is not one. Never throws.
 *
 * `Resource.value()` **throws** while the resource is in its error state, and a `defaultValue`
 * does not change that — it only covers idle and loading. That is a reasonable default for code
 * that wants to be told, and the wrong one for a template: Angular instantiates projected content
 * eagerly, so a panel's contents are evaluated even while the panel is showing a failure instead
 * of them. The throw then escapes as a template error and takes down the whole page — which is
 * precisely the failure mode this UI is built to avoid, arriving through the back door.
 *
 * So every list a template reads goes through here, and the panel's own error state remains the
 * one place a failure is reported.
 */
export function valueOr<T>(resource: ReadableResource<T | undefined>, fallback: T): Signal<T> {
  return computed(() => (resource.hasValue() ? (resource.value() as T) : fallback));
}
