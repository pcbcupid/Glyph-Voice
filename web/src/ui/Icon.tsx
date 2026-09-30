export type IconName =
  'history' | 'summary' | 'close' | 'copy' | 'trash' | 'settings' | 'wave' | 'arrow';
const paths: Record<IconName, string> = {
  history: 'M4 5h16M4 11h16M4 17h10M4 21h6',
  summary: 'M5 3h10l4 4v14H5zM15 3v5h4M8 12h8M8 16h5',
  close: 'm6 6 12 12M6 18 18 6',
  copy: 'M9 9h11v12H9zM5 16H3V3h11v2',
  trash: 'M3 6h18M9 6V3h6v3M5 6l1 15h12l1-15M10 10v7M14 10v7',
  settings: 'M4 7h16M4 17h16M8 4v6M16 14v6',
  wave: 'M3 10v4M7 6v12M12 2v20M17 6v12M21 10v4',
  arrow: 'M5 12h14m-6-6 6 6-6 6',
};
export function Icon({ name }: { name: IconName }) {
  return (
    <svg
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.6"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <path d={paths[name]} />
    </svg>
  );
}
