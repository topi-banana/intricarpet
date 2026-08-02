import glob
import json
import os


def read_prop(file_name: str, key: str) -> str:
	with open(file_name) as prop:
		return next(filter(
			lambda l: l.split('=', 1)[0].strip() == key,
			prop.readlines()
		)).split('=', 1)[1].lstrip()


target_subproject = os.environ.get('TARGET_SUBPROJECT', '')
with open('.github/workflows/matrix_includes.json') as f:
	matrix: list[dict] = json.load(f)

# One flat directory of jars, named `intricarpet-v<mod version>-mc<minecraft version>.jar`, because
# `jals package` writes one archive per invocation rather than a per-release build directory.
with open(os.environ['GITHUB_STEP_SUMMARY'], 'w') as f:
	f.write('## Build Artifacts Summary\n\n')
	f.write('| Release | for Minecraft | Files |\n')
	f.write('| --- | --- | --- |\n')

	for m in matrix:
		subproject = m['subproject_dir']
		if target_subproject != '' and subproject != target_subproject:
			continue
		properties = 'versions/{}/release.properties'.format(subproject)
		game_versions = read_prop(properties, 'game_versions').strip().replace('\\n', ', ')
		minecraft_version = read_prop(properties, 'minecraft_version').strip()
		file_names = glob.glob('build-artifacts/*-mc{}.jar'.format(minecraft_version))
		file_names = ', '.join(map(
			lambda fn: '`{}`'.format(os.path.basename(fn)),
			file_names
		))
		# Carpet is published in Mojang names only from 26.1; before that nothing here can remap it,
		# so the release lowers and lints but is not compiled. See docs/jals.md.
		if not file_names and read_prop(properties, 'carpet_namespace').strip() != 'official':
			file_names = '_not compiled: carpet is in the intermediary namespace_'
		f.write('| {} | {} | {} |\n'.format(subproject, game_versions, file_names))
