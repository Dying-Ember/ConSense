/** Search aliases only; these words never merge variables or change their definitions. */
export function questionSearchMatches(text: string, query: string) {
  const normalize = (value: string) => value.trim().toLocaleLowerCase().replace(/工期|(?:合同|合约|合約)期限|contract\s+(?:period|duration)/g, 'contract-duration')
  return normalize(text).includes(normalize(query))
}
