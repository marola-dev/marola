// Restores the `pathToRoot` global scaladoc's own scripts expect, without an inline
// <script> — read from <body data-path-to-root>, so the page keeps script-src 'self'. MIP-0044.
var pathToRoot = document.body.getAttribute("data-path-to-root") || "";
