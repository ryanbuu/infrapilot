# InfraPilot design system

Source: project-local ui-ux-pro-max. Queries: enterprise admin dashboard (design-system, density 8 / variance 3), dashboard navigation forms (Vue).

## Direction

Minimalism & Swiss Style fits the enterprise operations console. The catalog's landing/conversion sections are not verified for this authenticated management product and are excluded. Actual service metrics, resource tables and configuration forms define the page structure. No fabricated telemetry or charts.

## Palette and typography

Use Ant Design's default control algorithms with semantic fresh green surfaces. Light: layout #f3faf6, surface #ffffff, foreground #183b2c, secondary #3d5e4d, muted #526f60. Dark: layout #101d18, surface #182a22, elevated #21392e, foreground #e8f5ec, secondary #bfd6c7, muted #a3bfad. Primary actions use #238257; light links use #1c6b46 and dark links #8edbb0. Status colors retain their meaning. Status always includes text.

System sans-serif supports Chinese without external font requests; data values use system monospace. Headings 23/26px, data 13px, body/control text 14px and touch inputs >=16px. Contrast is checked separately for both schemes.

## Composition

240px navigation, 64px desktop header, 1720px content maximum. DNS service configuration is a nested group; search expands matching groups. Indicators are independent cards. Table and toolbar surfaces are distinct. Editors use clear sections and a separated confirmation footer. Panels use 12px corners; controls retain native Ant Design shapes. Mobile uses a solid drawer and resource cards.

## Interaction

Keep management URLs, preview/confirmation flows and protected record behavior. Provide a keyboard skip link, focus-visible controls, tap/focus alternatives for help, and reduced-motion styles. No new decorative animations. Keep all unedited network settings intact.

## Implementation

frontend/src/theme.ts supplies control tokens. console-design.css composes the workspace after existing component layout styles. All backend/API contracts are unchanged. Project-local .agents skill files remain user-owned and are not part of the application release.

Palette update: fresh green for both themes, including active navigation, control states, browser chrome and brand assets. Keep the current layout and typography.
