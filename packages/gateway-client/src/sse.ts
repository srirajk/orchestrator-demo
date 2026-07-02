/**
 * Minimal, dependency-free Server-Sent Events parsing over a fetch
 * `ReadableStream`. Used for both the OpenAI-compatible chat stream and the
 * gateway trace stream. We use fetch + a manual reader (not native
 * `EventSource`) because `EventSource` cannot attach an Authorization header.
 */

/** Extract the joined `data:` payload from one SSE event block, or null. */
export function sseDataFromBlock(block: string): string | null {
  const lines = block.split('\n')
  const dataLines = lines
    .filter((line) => line.startsWith('data:'))
    .map((line) => line.slice(5).trimStart())
  return dataLines.length > 0 ? dataLines.join('\n') : null
}

/** Split a buffer on SSE event boundaries (`\n\n`), returning the remainder. */
export function splitSseBlocks(buffer: string): { blocks: string[]; rest: string } {
  const normalized = buffer.replace(/\r\n/g, '\n')
  const blocks: string[] = []
  let rest = normalized
  let index = rest.indexOf('\n\n')

  while (index >= 0) {
    blocks.push(rest.slice(0, index))
    rest = rest.slice(index + 2)
    index = rest.indexOf('\n\n')
  }

  return { blocks, rest }
}

/**
 * Async iterator over the `data:` payloads of an SSE response body.
 * Yields raw strings (callers `JSON.parse` and/or check for the `[DONE]`
 * sentinel). Completes when the stream ends.
 */
export async function* iterateSseData(
  body: ReadableStream<Uint8Array>,
): AsyncGenerator<string, void, unknown> {
  const reader = body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''

  try {
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      const { blocks, rest } = splitSseBlocks(buffer)
      buffer = rest
      for (const block of blocks) {
        const data = sseDataFromBlock(block)
        if (data !== null) yield data
      }
    }
    // Flush any trailing block not terminated by a blank line.
    const tail = sseDataFromBlock(buffer.replace(/\r\n/g, '\n'))
    if (tail !== null) yield tail
  } finally {
    reader.releaseLock()
  }
}
