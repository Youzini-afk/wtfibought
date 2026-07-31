function normalizedName(siteName: string): string {
  return siteName.trim().replace(/[.。]+$/, '') || 'WTF';
}

/** Split whitespace and camel-case names without requiring admins to format the title manually. */
export function siteBrandWords(siteName: string): string[] {
  return normalizedName(siteName)
    .replace(/([a-z\d])([A-Z])/g, '$1 $2')
    .replace(/([A-Z]+)([A-Z][a-z])/g, '$1 $2')
    .split(/\s+/)
    .filter(Boolean);
}

/** Compact mark used by the navigation and the login-page corner brand. */
export function compactSiteBrand(siteName: string): string {
  const normalized = normalizedName(siteName);
  const words = siteBrandWords(normalized);
  if (words.length > 1) {
    return words.map(word => Array.from(word)[0] || '').join('').toUpperCase().slice(0, 8);
  }
  return Array.from(normalized).slice(0, 10).join('');
}

/** Balance a configurable site name over at most two hero lines. */
export function splitSiteBrand(siteName: string): [string, string?] {
  const words = siteBrandWords(siteName);
  if (words.length <= 1) return [words[0] || 'WTF'];

  let bestSplit = 1;
  let smallestDifference = Number.POSITIVE_INFINITY;
  for (let split = 1; split < words.length; split++) {
    const leftLength = words.slice(0, split).join(' ').length;
    const rightLength = words.slice(split).join(' ').length;
    const difference = Math.abs(leftLength - rightLength);
    if (difference < smallestDifference) {
      bestSplit = split;
      smallestDifference = difference;
    }
  }
  return [words.slice(0, bestSplit).join(' '), words.slice(bestSplit).join(' ')];
}
