// Every page served from app/src/main/assets, in PaneldServer route order. The mDNS switcher is not a
// route: it is part of the direct-view shell of every page, where the fixture roster makes it render.
import configure from './configure.mjs';

export const PAGES = [configure];
