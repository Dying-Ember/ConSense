import { sha256 } from '@noble/hashes/sha256'

export interface BrowserSha256Result {
  hex: string
  implementation: 'webcrypto' | 'portable'
}

/** Always hash the supplied bytes, including HTTP LAN origins without SubtleCrypto. */
export async function sha256Bytes(bytes: ArrayBuffer): Promise<BrowserSha256Result> {
  if (typeof globalThis.crypto?.subtle?.digest === 'function') {
    try {
      const digest = await globalThis.crypto.subtle.digest('SHA-256', bytes)
      return { hex: toHex(new Uint8Array(digest)), implementation: 'webcrypto' }
    } catch {
      // A disabled/unsupported WebCrypto API does not disable byte validation.
    }
  }
  const input = new Uint8Array(bytes), hash = sha256.create()
  const chunkSize = 1024 * 1024
  for (let offset = 0; offset < input.length; offset += chunkSize) {
    hash.update(input.subarray(offset, Math.min(offset + chunkSize, input.length)))
    if (offset + chunkSize < input.length) await new Promise<void>(resolve => setTimeout(resolve, 0))
  }
  return { hex: toHex(hash.digest()), implementation: 'portable' }
}

function toHex(bytes: Uint8Array): string {
  return Array.from(bytes, byte => byte.toString(16).padStart(2, '0')).join('')
}
