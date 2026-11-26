import { existsSync, readFileSync } from 'node:fs';
import { dirname, isAbsolute, relative, resolve, sep } from 'node:path';

export function discoverMavenReactorPoms(workspace = process.cwd()) {
  const root = resolve(workspace);
  const rootPom = resolve(root, 'pom.xml');
  if (!existsSync(rootPom)) throw new Error(`Maven reactor root POM does not exist: ${rootPom}.`);

  const discovered = [];
  const visited = new Set();
  const active = new Set();

  function visit(pomPath) {
    const absolutePom = resolve(pomPath);
    const rel = relative(root, absolutePom);
    if (rel.startsWith(`..${sep}`) || rel === '..' || isAbsolute(rel)) {
      throw new Error(`Maven reactor module escapes the workspace: ${pomPath}.`);
    }
    if (active.has(absolutePom)) throw new Error(`Maven reactor contains a module cycle at ${rel}.`);
    if (visited.has(absolutePom)) throw new Error(`Maven reactor declares the same POM more than once: ${rel}.`);
    if (!existsSync(absolutePom)) throw new Error(`Maven reactor module POM is missing: ${rel}.`);

    visited.add(absolutePom);
    active.add(absolutePom);
    discovered.push(rel.split(sep).join('/'));

    const xml = readFileSync(absolutePom, 'utf8').replace(/<!--[\s\S]*?-->/g, '');
    const modulesBlock = /<modules(?:\s[^>]*)?>([\s\S]*?)<\/modules>/.exec(xml)?.[1];
    if (modulesBlock !== undefined) {
      const modules = [...modulesBlock.matchAll(/<module(?:\s[^>]*)?>([^<]+)<\/module>/g)]
        .map((match) => match[1].trim());
      if (modules.length === 0) throw new Error(`Maven reactor module list is empty or malformed in ${rel}.`);
      for (const module of modules) {
        if (!module || module.includes('${') || module.includes('..')) {
          throw new Error(`Maven reactor module path is dynamic or unsafe in ${rel}: ${module}.`);
        }
        const modulePath = resolve(dirname(absolutePom), module);
        const modulePom = modulePath.toLowerCase().endsWith('.xml') ? modulePath : resolve(modulePath, 'pom.xml');
        visit(modulePom);
      }
    }

    active.delete(absolutePom);
  }

  visit(rootPom);
  return discovered;
}
