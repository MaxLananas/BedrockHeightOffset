// Renders the SVG logo sources to the PNG sizes shipped in assets/logo.
// Usage: node dev/release/render_logo.js  (requires @resvg/resvg-js, installed outside the repository)
const fs = require('fs');
const path = require('path');
const { Resvg } = require(process.env.RESVG_MODULE || '@resvg/resvg-js');

const root = path.resolve(__dirname, '..', '..', 'assets', 'logo');

function render(svgName, pngName, width) {
  const svg = fs.readFileSync(path.join(root, svgName), 'utf8');
  const png = new Resvg(svg, { fitTo: { mode: 'width', value: width } }).render().asPng();
  fs.writeFileSync(path.join(root, pngName), png);
  console.log(pngName, width + 'px', png.length + ' bytes');
}

for (const size of [1024, 512, 128, 32]) {
  render('hypso-logo.svg', `hypso-${size}.png`, size);
}
render('hypso-logo-mono.svg', 'hypso-mono-512.png', 512);
