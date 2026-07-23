// A stable id for "this user", used to own seat holds. sessionStorage is scoped
// to the browser tab, so two windows act as two different users — handy for
// demoing that one user's hold blocks another.
export function holderId() {
  let id = sessionStorage.getItem('cinetor-holder');
  if (!id) {
    id = (crypto.randomUUID && crypto.randomUUID()) || `h-${Math.random().toString(36).slice(2)}`;
    sessionStorage.setItem('cinetor-holder', id);
  }
  return id;
}
