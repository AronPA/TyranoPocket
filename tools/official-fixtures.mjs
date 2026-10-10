// Maintainer-only interoperability gate. Requires pinned @electron/asar 4.2.0.
// A successful run creates independent official archives; absent output is NOT a pass.
import { createPackageWithOptions, extractAll } from '@electron/asar';
import { mkdir, rm, readFile, readdir } from 'node:fs/promises';
import path from 'node:path';
const root = path.resolve(import.meta.dirname, '..');
const output = path.join(root, 'fixtures', 'official');
await rm(output, {recursive: true, force: true});await mkdir(output, {recursive: true});
const source = path.join(output, 'source');
extractAll(path.join(root, 'fixtures/A/Game/resources/app.asar'), source);
await createPackageWithOptions(source, path.join(output, 'app.asar'), {});
await createPackageWithOptions(source, path.join(output, 'unpacked.asar'), {unpack: '**/日本語*'});
console.log('Official ASAR read/write completed. Run production JVM interoperability checks next.');
