import '@testing-library/jest-dom'

if (typeof ResizeObserver === 'undefined') {
  global.ResizeObserver = class {
    observe() {}
    unobserve() {}
    disconnect() {}
  }
}

if (typeof PointerEvent === 'undefined') {
  global.PointerEvent = MouseEvent as any
}

if (typeof DOMRect === 'undefined') {
  global.DOMRect = class {
    x = 0; y = 0; width = 0; height = 0; top = 0; right = 0; bottom = 0; left = 0
    constructor(x = 0, y = 0, w = 0, h = 0) { this.x = x; this.y = y; this.width = w; this.height = h; this.top = y; this.right = x + w; this.bottom = y + h; this.left = x; }
    toJSON() { return {}; }
    static fromRect() { return new DOMRect(); }
  } as any
}