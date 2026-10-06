import { Landmark } from 'lucide-react';
import { logoUrl } from '../pack/brand';
import { useColorScheme } from '../theme/colorScheme';

/**
 * The brand's logo slot in the header, the public frame and the footer: the
 * pack's logo for the current colour scheme, or the core Landmark mark when
 * the pack ships none. Decorative (the brand name sits beside it), so it has
 * no alt text. An <img> cannot run script, even from an SVG.
 */
export default function BrandMark() {
  const url = logoUrl(useColorScheme());
  if (url) return <img className="brand-logo" src={url} alt="" aria-hidden="true" />;
  return (
    <span className="brand-mark" aria-hidden="true">
      <Landmark />
    </span>
  );
}
