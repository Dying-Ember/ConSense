export function focusableControls(container: HTMLElement) {
  return [...container.querySelectorAll<HTMLElement>('button, a[href], input, select, textarea, summary, [tabindex]')].filter(control => {
    const style = window.getComputedStyle(control)
    return control.tabIndex >= 0 && !control.matches(':disabled, [aria-disabled="true"]') && !control.closest('[hidden], [inert], [aria-hidden="true"]') && style.visibility !== 'hidden' && style.display !== 'none' && control.getClientRects().length > 0
  })
}
export function containTab(event: KeyboardEvent, container: HTMLElement) {
  const controls = focusableControls(container)
  const first = controls[0], last = controls.at(-1), focused = document.activeElement
  if (!first || !controls.some(control => control === focused)) {
    event.preventDefault(); ((event.shiftKey ? last : first) ?? container).focus({ preventScroll: true })
  } else if (event.shiftKey && focused === first || !event.shiftKey && focused === last) {
    event.preventDefault(); (event.shiftKey ? last : first)?.focus({ preventScroll: true })
  }
}
